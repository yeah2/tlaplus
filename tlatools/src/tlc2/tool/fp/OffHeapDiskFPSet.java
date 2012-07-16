// Copyright (c) 2003 Compaq Corporation.  All rights reserved.
// Portions Copyright (c) 2003 Microsoft Corporation.  All rights reserved.
package tlc2.tool.fp;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.rmi.RemoteException;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.management.NotCompliantMBeanException;

import tlc2.output.EC;
import tlc2.output.MP;
import tlc2.tool.TLCTrace;
import tlc2.tool.fp.management.DiskFPSetMXWrapper;
import tlc2.tool.management.TLCStandardMBean;
import tlc2.util.BufferedRandomAccessFile;
import tlc2.util.IdThread;
import tlc2.util.ReadersWriterLock;
import tlc2.util.Sort;
import util.Assert;
import util.FileUtil;

/**
 * A <code>DiskFPSet</code> is a subtype of <code>FPSet</code> that uses a
 * bounded amount of memory. Any fingerprints that don't fit in memory are
 * written to backing disk files. As required by the <code>FPSet</code> class,
 * this class's methods are thread-safe.
 * <p>
 * This implementation uses a single sorted disk file on which interpolated
 * binary search is performed. It keeps a separate
 * <TT>BufferedRandomAccessFile</TT> object open on the disk file per worker
 * thread. Hence, a new <TT>BufferedRandomAccessFile</TT> object does not have
 * to be created and destroyed on each <TT>contains</TT> operation. Multiple
 * disk seeks and reads may be required on each lookup, but in practice, the
 * numbers are very close to one (we have measured 1.05 seek operations and 1.1
 * read operations per lookup).
 * <p>
 * The implementation uses smart synchronization (using the
 * <code>ReadersWriterLock</code> class) so lookups on disk can be performed in
 * parallel.
 * <p>
 * We use the MSB of a fingerprint to indicate if it has been flushed to disk.
 * By doing so, we lose one bit of the fingerprint. However, we will get this
 * bit back if using MultiFPSet.
 */
@SuppressWarnings("serial")
public class OffHeapDiskFPSet extends FPSet implements FPSetStatistic {
	// fields
	/**
	 * upper bound on "tblCnt"
	 */
	protected final int maxTblCnt;
	/**
	 * mask for computing hash function
	 */
	protected int mask;
	protected int logMaxMemCnt;
	protected final int capacity;
	/**
	 * directory name for metadata
	 */
	protected String metadir;
	/**
	 * name of backing file
	 */
	protected String fpFilename;
	protected String tmpFilename;

	/**
	 * protects following fields
	 */
	protected final ReadersWriterLock rwLock;
	/**
	 * number of entries on disk. This is equivalent to the current number of fingerprints stored on disk.
	 * @see @see DiskFPSet#getFileCnt()
	 */
	protected long fileCnt;
	/**
	 * Has a flusher thread been selected? 
	 * 
	 * This is necessary because multiple threads can be in the second synchronized block 
	 * of the put(long) method. The first one is waiting to become the writer at rwLock.BeginWrite(),
	 * a second has the this.rwLock monitor and possibly inserts a second fp into memory.
	 */
	protected boolean flusherChosen;
	/**
	 * in-memory buffer of new entries
	 */
	protected ByteBuffer tbl;
	protected LongBuffer tblBuffer;
	/**
	 * number of entries in "tbl". This is equivalent to the current number of fingerprints stored in in-memory cache/index.
	 * @see OffHeapDiskFPSet#getTblCnt()
	 */
	protected int tblCnt; 

	/**
	 * Number of used slots in tbl by a bucket
	 * @see OffHeapDiskFPSet#getTblLoad()
	 */
	protected int tblLoad;
	
	/**
	 * Number of allocated bucket slots across the complete index table. tblCnt will always <= bucketCnt;
	 * @see OffHeapDiskFPSet#getBucketCapacity()
	 */
	protected long bucketsCapacity;
	
	/**
	 * one per worker thread
	 */
	protected RandomAccessFile[] braf;
	/**
	 * a pool of available brafs
	 */
	protected RandomAccessFile[] brafPool;
	protected int poolIndex;

	/**
	 * index of first fp on each disk page
	 * special case: last entry is last fp in file
	 * if <code>null</code>, no disk file exists yet
	 */
	protected long[] index;
	
	// statistics
	private long memHitCnt;
	private long diskLookupCnt;
	private long diskHitCnt;
	private long diskWriteCnt;
	private long diskSeekCnt;
	
	// indicate how many cp or disk grow in put(long) has occurred
	private int checkPointMark;
	private int growDiskMark;


	/**
	 * Log (base 2) of default number of new entries allowed to accumulate in
	 * memory before those entries are flushed to disk.
	 */
	protected static final int LogDefaultMaxTblCnt = 19;

	/**
	 * The load factor and initial capacity for the hashtable.
	 */
	protected static final int LogMaxLoad = 4;
	static final int InitialBucketCapacity = (1 << LogMaxLoad);
	protected static final int BucketSizeIncrement = 4;

	// computed constants
	static final int DefaultMaxTblCnt = (1 << LogDefaultMaxTblCnt);

	/* Number of fingerprints per braf buffer. */
	public static final int NumEntriesPerPage = 8192 / LongSize;
	
	/**
	 * This is (assumed to be) the auxiliary storage for a fingerprint that need
	 * to be respected to not cause an OOM.
	 * @see OffHeapDiskFPSet#flushTable()
	 * @see OffHeapDiskFPSet#index
	 */
	protected double getAuxiliaryStorageRequirement() {
		return 2.5d;
	}
	
	private TLCStandardMBean diskFPSetMXWrapper;
	private int moveBy;

	/**
	 * Construct a new <code>DiskFPSet2</code> object whose internal memory
	 * buffer of new fingerprints can contain up to
	 * <code>DefaultMaxTblCnt</code> entries. When the buffer fills up, its
	 * entries are atomically flushed to the FPSet's backing disk file.
	 * 
	 * @param maxInMemoryCapacity The number of fingerprints (not memory) this DiskFPSet should maximally store in-memory.
	 * @throws RemoteException
	 */
	protected OffHeapDiskFPSet(final long maxInMemoryCapacity) throws RemoteException {
		this.rwLock = new ReadersWriterLock();
		this.fileCnt = 0;
		this.flusherChosen = false;

		long maxMemCnt = (long) (maxInMemoryCapacity / getAuxiliaryStorageRequirement());

		// default if not specific value given
		if ((maxMemCnt - LogMaxLoad) <= 0) {
			maxMemCnt = DefaultMaxTblCnt;
		}
		
		// half maxMemCnt until it hits 1
		// to approximate 2^n ~= maxMemCnt
		this.logMaxMemCnt = 1;
		maxMemCnt--;
		while (maxMemCnt > 1) {
			maxMemCnt = maxMemCnt / 2;
			logMaxMemCnt++;
		}

		// guard against underflow
		// LL modified error message on 7 April 2012
		Assert.check(logMaxMemCnt - LogMaxLoad >= 0, "Underflow when computing DiskFPSet");
		this.capacity = 1 << (logMaxMemCnt - LogMaxLoad);
		
		// instead of changing maxTblCnd to long and pay an extra price when 
		// comparing int and long every time put(long) is called, we set it to 
		// Integer.MAX_VALUE instead. capacity can never grow bigger 
		// (unless java starts supporting 64bit array sizes)
		//
		// maxTblCnt mathematically has to be an upper limit for the in-memory storage 
		// so that it a disk flush occurs before an _evenly_ distributed fp distribution fills up 
		// the collision buckets to a size that exceeds the VM limit (unevenly distributed 
		// fp distributions can still cause a OutOfMemoryError which this guard).
		this.maxTblCnt = (logMaxMemCnt >= 31) ? Integer.MAX_VALUE : (1 << logMaxMemCnt); // maxTblCnt := 2^logMaxMemCnt

		// guard against negative maxTblCnt
		// LL modified error message on 7 April 2012
		Assert.check(maxTblCnt > capacity && capacity > tblCnt,
				"negative maxTblCnt");

		this.tblCnt = 0;
		
		
		//TODO impl preBits
		int preBits = 0;
		// To pre-sort fingerprints in memory, use n MSB fp bits for the
		// index. However, we cannot use the 31 bit, because it is used to
		// indicate if a fp has been flushed to disk. Hence we use the first n
		// bits starting from the second most significant bit.
		this.moveBy = (31 - preBits) - (logMaxMemCnt - LogMaxLoad);
		this.mask = (capacity - 1) << moveBy;
		this.index = null;
		
		int ca = capacity * InitialBucketCapacity * LongSize;
		Assert.check(ca > 0, EC.GENERAL);
		this.tbl = ByteBuffer.allocateDirect(ca);
		Assert.check(this.tbl.capacity() > maxTblCnt, EC.GENERAL);
		this.tblBuffer = this.tbl.asLongBuffer();
		this.collisionBucket = new TreeSet<Long>();
		
		try {
			diskFPSetMXWrapper = new DiskFPSetMXWrapper(this);
		} catch (NotCompliantMBeanException e) {
			// not expected to happen
			// would cause JMX to be broken, hence just log and continue
			MP.printWarning(
					EC.GENERAL,
					"Failed to create MBean wrapper for DiskFPSet. No statistics/metrics will be avaiable.",
					e);
			diskFPSetMXWrapper = TLCStandardMBean.getNullTLCStandardMBean();
		}
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#init(int, java.lang.String, java.lang.String)
	 */
	public final void init(int numThreads, String metadir, String filename)
			throws IOException {
		this.metadir = metadir;
		// set the filename
		// concat here to not do it every time in mergeEntries 
		filename = metadir + FileUtil.separator + filename;
		this.tmpFilename = filename + ".tmp";
		this.fpFilename = filename + ".fp";
		
		// allocate array of BufferedRAF objects (+1 for main thread)
		this.braf = new BufferedRandomAccessFile[numThreads];
		this.brafPool = new BufferedRandomAccessFile[5];
		this.poolIndex = 0;

		
		try {
			// create/truncate backing file:
			FileOutputStream f = new FileOutputStream(this.fpFilename);
			f.close();

			// open all "this.braf" and "this.brafPool" objects on currName:
			for (int i = 0; i < numThreads; i++) {
				this.braf[i] = new BufferedRandomAccessFile(
						this.fpFilename, "r");
			}
			for (int i = 0; i < brafPool.length; i++) {
				this.brafPool[i] = new BufferedRandomAccessFile(
						this.fpFilename, "r");
			}
		} catch (IOException e) {
			// fatal error -- print error message and exit
			String message = MP.getMessage(EC.SYSTEM_UNABLE_TO_OPEN_FILE,
					new String[] { this.fpFilename, e.getMessage() });
			throw new IOException(message);
		}
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#size()
	 */
	public final long size() {
		synchronized (this.rwLock) {
			return this.tblCnt + this.fileCnt;
		}
	}

	public final long sizeof() {
		synchronized (this.rwLock) {
			long size = 44; // approx size of this DiskFPSet object
			size += this.tbl.capacity() * LongSize;
			size += getIndexCapacity() * 4;
			size += collisionBucket.size() * LongSize; // ignoring the internal TreeSet overhead here
			return size;
		}
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#finalize()
	 */
	public final void finalize() {
		/* Close any backing disk files in use by this object. */
		for (int i = 0; i < this.braf.length; i++) {
			try {
				this.braf[i].close();
			} catch (IOException e) { /* SKIP */
			}
		}
		for (int i = 0; i < this.brafPool.length; i++) {
			try {
				this.brafPool[i].close();
			} catch (IOException e) { /* SKIP */
			}
		}
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#addThread()
	 */
	public final void addThread() throws IOException {
		synchronized (this.rwLock) {
			this.rwLock.BeginWrite();

			int len = this.braf.length;
			RandomAccessFile[] nraf = new BufferedRandomAccessFile[len + 1];
			for (int i = 0; i < len; i++) {
				nraf[i] = this.braf[i];
			}
			nraf[len] = new BufferedRandomAccessFile(this.fpFilename, "r");
			this.braf = nraf;

			this.rwLock.EndWrite();
		}
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#put(long)
	 * 
     * 0 and {@link Long#MIN_VALUE} always return false
     * 
     * Locking is as follows:
     * 
     * Acquire mem read lock
     * Acquire disk read lock
     * Release mem read lock
     * 
     * Acquire mem read lock
     * Release disk read lock // interleaved 
     *  insert into mem
     * Acquire disk write lock (might cause potential writer to wait() which releases mem read lock (monitor))
     * 	flushToDisk
     * Release disk write lock
     * Release mem read lock
     * 
     * asserts:
     * - Exclusive access to disk and memory for a writer
     * 
	 */
	public final boolean put(long fp) throws IOException {
		// zeros the msb
		long fp0 = fp & 0x7FFFFFFFFFFFFFFFL;
		synchronized (this.rwLock) {
			// First, look in in-memory buffer
			if (this.memLookup(fp0)) {
				this.memHitCnt++;
				return true;
			}

			// blocks => wait() if disk is being re-written 
			// (means the current thread returns rwLock monitor)
			// Why not return monitor first and then acquire read lock?
			// => prevent deadlock by acquiring threads in same order? 
			this.rwLock.BeginRead();
			this.diskLookupCnt++;
		}

		// next, look on disk
		boolean diskHit = this.diskLookup(fp0);

		// end read; add to memory buffer if necessary
		synchronized (this.rwLock) {
			this.rwLock.EndRead();

			// In event of disk hit, return
			if (diskHit) {
				this.diskHitCnt++;
				return true;
			}

			// if disk lookup failed, add to memory buffer
			if (this.memInsert(fp0)) {
				this.memHitCnt++;
				return true;
			}

			// test if buffer is full
			//TODO does not take the bucket load factor into account?
			// Buckets can grow beyond VM heap size if:
			// A) the FP distribution causes the index tbl to be unevenly populated.
			// B) the FP distribution reassembles linear fill-up/down which 
			// causes tblCnt * buckets with initial load factor to be allocated.
			if ((this.tblCnt >= this.maxTblCnt || sizeOfCollisionBucketExceeds(.05d)) && !this.flusherChosen) {
				// block until there are no more readers
				this.flusherChosen = true;
				this.rwLock.BeginWrite();

				// statistics
				growDiskMark++;
				
				// flush memory entries to disk
				this.flushTable();

				// finish writing
				this.rwLock.EndWrite();
				this.flusherChosen = false;
			}
			return false;
		}
	}
	
	/**
	 * @param limit A limit the collsionBucket is not allowed to exceed
	 * @return The proportional size of the collision bucket compared to the
	 *         size of the set.
	 */
	private boolean sizeOfCollisionBucketExceeds(final double limit) {
		// the fraction of collisionBucket size compared to the tbl size 
		final double dSize = (double) collisionBucket.size();
		final double dTblcnt = (double) tblCnt;
		return dSize / dTblcnt >= limit;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#contains(long)
	 * 
     * 0 and {@link Long#MIN_VALUE} always return false
	 */
	public final boolean contains(long fp) throws IOException {
		// zeros the msb
		long fp0 = fp & 0x7FFFFFFFFFFFFFFFL;
		synchronized (this.rwLock) {
			// First, look in in-memory buffer
			if (this.memLookup(fp0)) {
				this.memHitCnt++;
				return true;
			}

			// block if disk is being re-written
			this.rwLock.BeginRead();
			this.diskLookupCnt++;
		}

		// next, look on disk
		boolean diskHit = this.diskLookup(fp0);
		// increment while still locked
		if(diskHit) {
			diskHitCnt++;
		}

		// end read; add to memory buffer if necessary
		synchronized (this.rwLock) {
			this.rwLock.EndRead();
		}
		return diskHit;
	}

	/**
	 * calculate hash value (just n least significat bits of fp) which is used as an index address
	 * @param fp
	 * @return
	 */
	protected int getLogicalPosition(long fp) {
		// calculate hash value (just n most significant bits of fp) which is
		// used as an index address
		long l = fp >>> 32;
		long l2 = l & this.mask;
		int index = (int) l2 >> moveBy;
		int position = index * InitialBucketCapacity;
		Assert.check(position < tblBuffer.capacity(), EC.GENERAL);
		return position;
	}

	/**
	 * @param fp The fingerprint to lookup in memory
	 * @return true iff "fp" is in the hash table. 
	 */
	final boolean memLookup(long fp) {
		final int position = getLogicalPosition(fp);
		
		// Linearly search the logical bucket; 0L is an invalid fp and marks the
		// end of the allocated bucket
		long l = -1L;
		for (int i = 0; i < InitialBucketCapacity && l != 0L; i++) {
			l = tblBuffer.get(position + i);
			// zero the long msb (which is 1 if fp has been flushed to disk)
			if (fp == (l & 0x7FFFFFFFFFFFFFFFL)) {
				return true;
			}
		}
		return collisionBucket.contains(fp);
	}
	
	/**
	 * Return "true" if "fp" is contained in the hash table; otherwise, insert
	 * it and return "false". Precondition: msb(fp) = 0
	 */
	final boolean memInsert(long fp) {
		final int position = getLogicalPosition(fp);

		long l = -1;
		int freePosition = -1;
		for (int i = 0; i < InitialBucketCapacity && l != 0L; i++) {
			l = tblBuffer.get(position + i);
			// zero the long msb (which is 1 if fp has been flushed to disk)
			if (fp == (l & 0x7FFFFFFFFFFFFFFFL)) {
				return true;
			} else if (l == 0L && freePosition == -1) {
				// empty or disk written slot found, simply insert at _current_ position
				tblBuffer.put(position + i, fp);
				this.tblCnt++;
				return false;
			} else if (l < 0L && freePosition == -1) {
				// record free (disk written fp) slot
				freePosition = position + i;
			}
		}
		
		// index slot overflow, thus add to collisionBucket
		if (!collisionBucket.contains(fp)) {
			if (freePosition > -1) {
				tblBuffer.put(freePosition, fp);
				this.tblCnt++;
				return false;
			} else {
				collisionBucket.add(fp);
				this.tblCnt++;
				return false;
			}
		}
		return true;
	}

	/**
	 * Look on disk for the fingerprint "fp". This method requires that
	 * "this.rwLock" has been acquired for reading by the caller.
	 * @param fp The fingerprint to lookup on disk
	 * @return true iff fp is on disk
	 */
	final boolean diskLookup(long fp) throws IOException {
		if (this.index == null)
			return false;
		// search in index for position to seek to
		// do interpolated binary search
		final int indexLength = this.index.length;
		int loPage = 0, hiPage = indexLength - 1;
		long loVal = this.index[loPage];
		long hiVal = this.index[hiPage];

		// Test boundary cases (if not inside interval)
		if (fp < loVal || fp > hiVal)
			return false;
		if (fp == hiVal) // why not check loVal? memLookup would have found it already!	
			return true;
		double dfp = (double) fp;

		// a) find disk page that would potentially contain the fp. this.index contains 
		// the first fp of each disk page
		while (loPage < hiPage - 1) {
			/*
			 * Invariant: If "fp" exists in the file, the (zero-based) page
			 * number within the file on which it occurs is in the half-open
			 * interval "[loPage, hiPage)".
			 * 
			 * loVal <= fp < hiVal exists x: loPage < x < hiPage
			 */
			double dhi = (double) hiPage;
			double dlo = (double) loPage;
			double dhiVal = (double) hiVal;
			double dloVal = (double) loVal;
			
			int midPage = (loPage + 1)
					+ (int) ((dhi - dlo - 1.0) * (dfp - dloVal) / (dhiVal - dloVal));
			if (midPage == hiPage)
				midPage--; // Needed due to limited precision of doubles

			Assert.check(loPage < midPage && midPage < hiPage,
					EC.SYSTEM_INDEX_ERROR);
			long v = this.index[midPage];
			if (fp < v) {
				hiPage = midPage;
				hiVal = v;
			} else if (fp > v) {
				loPage = midPage;
				loVal = v;
			} else {
				// given fp happens to be in index file
				return true;
			}
		}
		// no page is in between loPage and hiPage at this point
		Assert.check(hiPage == loPage + 1, EC.SYSTEM_INDEX_ERROR);

		boolean diskHit = false;
		long midEntry = -1L;
		// lower bound for the interval search in 
		long loEntry = ((long) loPage) * NumEntriesPerPage;
		// upper bound for the interval search in 
		long hiEntry = ((loPage == indexLength - 2) ? this.fileCnt - 1
				: ((long) hiPage) * NumEntriesPerPage);
		try {
			// b0) open file for reading that is associated with current thread
			RandomAccessFile raf;
			int id = IdThread.GetId(this.braf.length);
			if (id < this.braf.length) {
				raf = this.braf[id];
			} else {
				synchronized (this.brafPool) {
					if (this.poolIndex < this.brafPool.length) {
						raf = this.brafPool[this.poolIndex++];
					} else {
						raf = new BufferedRandomAccessFile(
								this.fpFilename, "r");
					}
				}
			}
			
			// b1) do interpolated binary search on disk page determined by a)

			while (loEntry < hiEntry) {
				/*
				 * Invariant: If "fp" exists in the file, its (zero-based)
				 * position within the file is in the half-open interval
				 * "[loEntry, hiEntry)".
				 */
				midEntry = calculateMidEntry(loVal, hiVal, dfp, loEntry, hiEntry);

				Assert.check(loEntry <= midEntry && midEntry < hiEntry,
						EC.SYSTEM_INDEX_ERROR);
				// midEntry calculation done on logical indices,
				// addressing done on bytes, thus convert to long-addressing (* LongSize)
				raf.seek(midEntry * LongSize);
				diskSeekCnt++;
				long v = raf.readLong();

				if (fp < v) {
					hiEntry = midEntry;
					hiVal = v;
				} else if (fp > v) {
					loEntry = midEntry + 1;
					loVal = v;
				} else {
					diskHit = true;
					break;
				}
			}
			// b2) done doing disk search -> close file (finally candidate? => not really because if we exit with error, TLC exits)
			if (id >= this.braf.length) {
				synchronized (this.brafPool) {
					if (this.poolIndex > 0) {
						this.brafPool[--this.poolIndex] = raf;
					} else {
						raf.close();
					}
				}
			}
		} catch (IOException e) {
			if(midEntry * LongSize < 0) {
			 // LL modified error message on 7 April 2012
				MP.printError(EC.GENERAL, new String[]{"looking up a fingerprint, and" + 
			            "\nmidEntry turned negative (loEntry, midEntry, hiEntry, loVal, hiVal): ",
						Long.toString(loEntry) +" ", Long.toString(midEntry) +" ", Long.toString(hiEntry) +" ", Long.toString(loVal) +" ", Long.toString(hiVal)}, e);
			}
			MP.printError(EC.SYSTEM_DISKGRAPH_ACCESS, e);
			throw e;
		}
		return diskHit;
	}

	/**
	 * Calculates a mid entry where to divide the interval
	 * 
	 * @param loVal Smallest fingerprint in this interval {@link Long#MIN_VALUE} to {@link Long#MAX_VALUE}
	 * @param hiVal Biggest fingerprint in this interval {@link Long#MIN_VALUE} to {@link Long#MAX_VALUE}
	 * @param fp The fingerprint we are searching for {@link Long#MIN_VALUE} to {@link Long#MAX_VALUE}
	 * @param loEntry low position/bound index  0 to {@link Long#MAX_VALUE}
	 * @param hiEntry high position/bound index loEntry to {@link Long#MAX_VALUE}
	 * 
	 * @return A mid entry where to divide the interval
	 */
	long calculateMidEntry(long loVal, long hiVal, final double dfp, long loEntry, long hiEntry) {

		final double dhi = (double) hiEntry;
		final double dlo = (double) loEntry;
		final double dhiVal = (double) hiVal;
		final double dloVal = (double) loVal;
		
		long midEntry = loEntry
				+ (long) ((dhi - dlo) * (dfp - dloVal) / (dhiVal - dloVal));
		
		if (midEntry == hiEntry) {
			midEntry--;
		}

		return midEntry;
	}

	/**
	 * Flush the contents of in-memory "this.tbl" to the backing disk file, and update
	 * "this.index". This method requires that "this.rwLock" has been acquired
	 * for writing by the caller, and that the mutex "this.rwLock" is also held.
	 */
	void flushTable() throws IOException {
		if (this.tblCnt == 0)
			return;
		
		System.out.println("Flushing FPSet for the " + growDiskMark + " time...");
		if (sizeOfCollisionBucketExceeds(.05d)) {
			System.out.println("...due to collisionBucket size limit");
		}

//		// reset statistic counters
//		this.memHitCnt = 0;
//
//		this.diskHitCnt = 0;
//		this.diskWriteCnt = 0;
//		this.diskSeekCnt = 0;
//		this.diskLookupCnt = 0;
		
		// merge array with disk file
		try {
			this.mergeNewEntries();
		} catch (IOException e) {
			String msg = "Error: merging entries into file "
					+ this.fpFilename + "  " + e;
			throw new IOException(msg);
		}
		this.tblCnt = 0;
		this.bucketsCapacity = 0;
		this.tblLoad = 0;
	}

	/**
	 * Merge the values in "buff" into this FPSet's backing disk file. The
	 * values in "buff" are required to be in sorted order, and the write lock
	 * associated with "this.rwLock" must be held, as must the mutex
	 * "this.rwLock" itself.
	 */
	private final void mergeNewEntries() throws IOException {
		// Implementation Note: Unfortunately, because the RandomAccessFile
		// class (and hence, the BufferedRandomAccessFile class) does not
		// provide a way to re-use an existing RandomAccessFile object on
		// a different file, this implementation must close all existing
		// files and re-allocate new BufferedRandomAccessFile objects.

		// close existing files (except brafPool[0])
		for (int i = 0; i < this.braf.length; i++) {
			this.braf[i].close();
		}
		for (int i = 1; i < this.brafPool.length; i++) {
			this.brafPool[i].close();
		}

		// create temporary file
		File tmpFile = new File(tmpFilename);
		tmpFile.delete();
		RandomAccessFile tmpRAF = new BufferedRandomAccessFile(tmpFile, "rw");
		RandomAccessFile raf = this.brafPool[0];
		raf.seek(0);

		// merge
		this.mergeNewEntries(raf, tmpRAF);

		// clean up
		raf.close();
		tmpRAF.close();
		String realName = this.fpFilename;
		File currFile = new File(realName);
		currFile.delete();
		boolean status = tmpFile.renameTo(currFile);
		Assert.check(status, EC.SYSTEM_UNABLE_NOT_RENAME_FILE);

		// reopen a BufferedRAF for each thread
		for (int i = 0; i < this.braf.length; i++) {
			// Better way would be to provide method BRAF.open
			this.braf[i] = new BufferedRandomAccessFile(realName, "r");
		}
		for (int i = 0; i < this.brafPool.length; i++) {
			// Better way would be to provide method BRAF.open
			this.brafPool[i] = new BufferedRandomAccessFile(realName, "r");
		}
		this.poolIndex = 0;
	}

	private final void mergeNewEntries(long[] buff, int buffLen)
			throws IOException {
		// create temporary file
		File tmpFile = new File(tmpFilename);
		tmpFile.delete();
		RandomAccessFile tmpRAF = new BufferedRandomAccessFile(tmpFile, "rw");
		File currFile = new File(this.fpFilename);
		RandomAccessFile currRAF = new BufferedRandomAccessFile(currFile, "r");

		// merge
		this.mergeNewEntries(currRAF, tmpRAF);

		// clean up
		currRAF.close();
		tmpRAF.close();
		currFile.delete();
		boolean status = tmpFile.renameTo(currFile);
		Assert.check(status, EC.SYSTEM_UNABLE_NOT_RENAME_FILE);
	}

	protected int currIndex;
	protected int counter;

	protected final void writeFP(RandomAccessFile outRAF, long fp)
			throws IOException {
		outRAF.writeLong(fp);
		diskWriteCnt++;
		// update in-memory index file
		if (this.counter == 0) {
			this.index[this.currIndex++] = fp;
			this.counter = NumEntriesPerPage;
		}
		this.counter--;
	}

	private final void mergeNewEntries(RandomAccessFile inRAF, RandomAccessFile outRAF) throws IOException {
		final long buffLen = this.tblCnt;
		ByteBufferIterator itr = new ByteBufferIterator(this.tbl, collisionBucket, buffLen);
		
		// Precompute the maximum value of the new file
		long maxVal = itr.getLast();
		if (this.index != null) {
			maxVal = Math.max(maxVal, this.index[this.index.length - 1]);
		}

		//TODO this can cause a NegativeArraySizeException if fileCnt becomes sufficiently large
		int indexLen = (int) ((this.fileCnt + buffLen - 1) / NumEntriesPerPage) + 2;
		this.index = new long[indexLen];
		this.index[indexLen - 1] = maxVal;
		this.currIndex = 0;
		this.counter = 0;

		// initialize positions in "buff" and "inRAF"
		long value = 0L; // initialize only to make compiler happy
		boolean eof = false;
		if (this.fileCnt > 0) {
			try {
				value = inRAF.readLong();
			} catch (EOFException e) {
				eof = true;
			}
		} else {
			eof = true;
		}

		// merge while both lists still have elements remaining
		long fp = itr.next();
		while (!eof) {
			if (value < fp || !itr.hasNext()) { // check for itr.hasNext() here to write last value when itr is used up.
				this.writeFP(outRAF, value);
				try {
					value = inRAF.readLong();
				} catch (EOFException e) {
					eof = true;
				}
			} else {
				// prevent converting every long to String when assertion holds (this is expensive)
				if (value == fp) {
					Assert.check(false, EC.TLC_FP_VALUE_ALREADY_ON_DISK,
							String.valueOf(value));
				}
				this.writeFP(outRAF, fp);
				// we used one fp up, thus move to next one
				fp = itr.next();
			}
		}

		// write elements of remaining list
		if (eof) {
			while (fp > 0L) {
				this.writeFP(outRAF, fp);
				if (!itr.hasNext()) {
					break;
				}
				fp = itr.next();
			}
		} else {
			do {
				this.writeFP(outRAF, value);
				try {
					value = inRAF.readLong();
				} catch (EOFException e) {
					eof = true;
				}
			} while (!eof);
		}
		Assert.check(itr.reads() == buffLen, EC.GENERAL);

		// currIndex is amount of disk writes
		Assert.check(this.currIndex == indexLen - 1, EC.SYSTEM_INDEX_ERROR);

		// maintain object invariants
		this.fileCnt += buffLen;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#close()
	 */
	public final void close() {
		// close JMX stats
		diskFPSetMXWrapper.unregister();
		
		for (int i = 0; i < this.braf.length; i++) {
			try {
				this.braf[i].close();
			} catch (IOException e) { /* SKIP */
			}
		}
		for (int i = 0; i < this.brafPool.length; i++) {
			try {
				this.brafPool[i].close();
			} catch (IOException e) { /* SKIP */
			}
		}
		this.poolIndex = 0;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#exit(boolean)
	 */
	public final void exit(boolean cleanup) throws IOException {
		if (cleanup) {
			// Delete the metadata directory:
			FileUtil.deleteDir(this.metadir, true);
		}
		String hostname = InetAddress.getLocalHost().getHostName();
		MP.printMessage(EC.TLC_FP_COMPLETED, hostname);

		System.exit(0);
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#checkFPs()
	 */
	public final double checkFPs() throws IOException {
		this.flushTable(); // No need for any lock here
		RandomAccessFile braf = new BufferedRandomAccessFile(
				this.fpFilename, "r");
		long fileLen = braf.length();
		long dis = Long.MAX_VALUE;
		if (fileLen > 0) {
			long x = braf.readLong();
			while (braf.getFilePointer() < fileLen) {
				long y = braf.readLong();
				long dis1 = y - x;
				if (dis1 >= 0) {
					dis = Math.min(dis, dis1);
				}
				x = y;
			}
		}
		braf.close();
		return (1.0 / dis);
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#beginChkpt(java.lang.String)
	 */
	public final void beginChkpt(String fname) throws IOException {
		synchronized (this.rwLock) {
			this.flusherChosen = true;
			this.rwLock.BeginWrite();
			this.flushTable();
			FileUtil.copyFile(this.fpFilename,
					this.getChkptName(fname, "tmp"));
			checkPointMark++;
			this.rwLock.EndWrite();
			this.flusherChosen = false;
		}
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#commitChkpt(java.lang.String)
	 */
	public final void commitChkpt(String fname) throws IOException {
		File oldChkpt = new File(this.getChkptName(fname, "chkpt"));
		File newChkpt = new File(this.getChkptName(fname, "tmp"));
		if (!newChkpt.renameTo(oldChkpt)) {
			throw new IOException("DiskFPSet.commitChkpt: cannot delete "
					+ oldChkpt);
		}
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#recover(java.lang.String)
	 */
	public final void recover(String fname) throws IOException {
		RandomAccessFile chkptRAF = new BufferedRandomAccessFile(
				this.getChkptName(fname, "chkpt"), "r");
		RandomAccessFile currRAF = new BufferedRandomAccessFile(
				this.fpFilename, "rw");

		this.fileCnt = chkptRAF.length() / LongSize;
		int indexLen = (int) ((this.fileCnt - 1) / NumEntriesPerPage) + 2;
		this.index = new long[indexLen];
		this.currIndex = 0;
		this.counter = 0;

		long fp = 0L;
		try {
			while (true) {
				fp = chkptRAF.readLong();
				this.writeFP(currRAF, fp);
			}
		} catch (EOFException e) {
			Assert.check(this.currIndex == indexLen - 1, EC.SYSTEM_INDEX_ERROR);
			this.index[indexLen - 1] = fp;
		}

		chkptRAF.close();
		currRAF.close();

		// reopen a BufferedRAF for each thread
		for (int i = 0; i < this.braf.length; i++) {
			// Better way would be to provide method BRAF.open
			// close and reopen
			this.braf[i].close();
			this.braf[i] = new BufferedRandomAccessFile(this.fpFilename,
					"r");
		}
		for (int i = 0; i < this.brafPool.length; i++) {
			// Better way would be to provide method BRAF.open
			// close and reopen
			this.brafPool[i].close();
			this.brafPool[i] = new BufferedRandomAccessFile(
					this.fpFilename, "r");
		}
		this.poolIndex = 0;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#beginChkpt()
	 */
	public final void beginChkpt() throws IOException {
		// @see tlc2.tool.fp.DiskFPSet.commitChkpt()
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#commitChkpt()
	 */
	public final void commitChkpt() throws IOException { 
		/* SKIP */
		// DiskFPSet checkpointing is a no-op, because DiskFPSet recreates 
		// the fingerprints from the TLCTrace file. Not from its own .fp file. 
	}

	private long[] recoveryBuff = null;
	private int recoveryIdx = -1;

	//TODO replace SortedSet with cheaper/faster long[]?!
	/**
	 * A bucket containing collision elements
	 */
	private SortedSet<Long> collisionBucket;

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#prepareRecovery()
	 */
	public final void prepareRecovery() throws IOException {
		// First close all "this.braf" and "this.brafPool" objects on currName:
		for (int i = 0; i < this.braf.length; i++) {
			this.braf[i].close();
		}
		for (int i = 0; i < this.brafPool.length; i++) {
			this.brafPool[i].close();
		}

		recoveryBuff = new long[1 << 21];
		recoveryIdx = 0;
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#recoverFP(long)
	 */
	public final void recoverFP(long fp) throws IOException {
		recoveryBuff[recoveryIdx++] = (fp & 0x7FFFFFFFFFFFFFFFL);
		if (recoveryIdx == recoveryBuff.length) {
			Sort.LongArray(recoveryBuff, recoveryIdx);
			this.mergeNewEntries(recoveryBuff, recoveryIdx);
			recoveryIdx = 0;
		}
	}

	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#completeRecovery()
	 */
	public final void completeRecovery() throws IOException {
		Sort.LongArray(recoveryBuff, recoveryIdx);
		this.mergeNewEntries(recoveryBuff, recoveryIdx);
		recoveryBuff = null;
		recoveryIdx = -1;

		// Reopen a BufferedRAF for each thread
		for (int i = 0; i < this.braf.length; i++) {
			this.braf[i] = new BufferedRandomAccessFile(this.fpFilename,
					"r");
		}
		for (int i = 0; i < this.brafPool.length; i++) {
			this.brafPool[i] = new BufferedRandomAccessFile(
					this.fpFilename, "r");
		}
		this.poolIndex = 0;
	}

	
	/* (non-Javadoc)
	 * @see tlc2.tool.fp.FPSet#recover()
	 */
	public final void recover() throws IOException {
		this.prepareRecovery();

		long recoverPtr = TLCTrace.getRecoverPtr();
		@SuppressWarnings("resource")
		RandomAccessFile braf = new BufferedRandomAccessFile(
				TLCTrace.getFilename(), "r");
		while (braf.getFilePointer() < recoverPtr) {
			// drop readLongNat
			if (braf.readInt() < 0)
				braf.readInt();

			long fp = braf.readLong();
			this.recoverFP(fp);
		}

		this.completeRecovery();
	}

	private String getChkptName(String fname, String name) {
		return this.metadir + FileUtil.separator + fname + ".fp." + name;
	}

	/**
	 * @return the bucketsCapacity counting all allocated (used and unused) fp slots in the in-memory storage.
	 */
	public long getBucketCapacity() {
		return bucketsCapacity;
	}
	
	/**
	 * @return The allocated (used and unused) array length of the first level in-memory storage.
	 */
	public int getTblCapacity() {
		return tbl.capacity();
	}

	/**
	 * @return the index.length
	 */
	public int getIndexCapacity() {
		if(index == null) {
			return 0;
		}
		return index.length;
	}

	/**
	 * @return {@link OffHeapDiskFPSet#getBucketCapacity()} + {@link OffHeapDiskFPSet#getTblCapacity()} + {@link OffHeapDiskFPSet#getIndexCapacity()}.
	 */
	public long getOverallCapacity() {
		return getBucketCapacity() + getTblCapacity() + getIndexCapacity();
	}
	
	/**
	 * @return	Number of used slots in tbl by a bucket
	 * {@link OffHeapDiskFPSet#getTblLoad()} <= {@link OffHeapDiskFPSet#getTblCnt()}
	 */
	public int getTblLoad() {
		return tblLoad;
	}
	
	/**
	 * @return the amount of fingerprints stored in memory. This is less or equal to {@link OffHeapDiskFPSet#getTblCnt()} depending on if there collision buckets exist. 
	 */
	public int getTblCnt() {
		return tblCnt;
	}
	
	/**
	 * @return the maximal amount of fingerprints stored in memory. 
	 */
	public int getMaxTblCnt() {
		return maxTblCnt;
	}
	
	/**
	 * @return the amount of fingerprints stored on disk
	 */
	public long getFileCnt() {
		return fileCnt;
	}
	
	/**
	 * @return the diskLookupCnt
	 */
	public long getDiskLookupCnt() {
		return diskLookupCnt;
	}

	/**
	 * @return the diskHitCnt
	 */
	public long getMemHitCnt() {
		return memHitCnt;
	}

	/**
	 * @return the diskHitCnt
	 */
	public long getDiskHitCnt() {
		return diskHitCnt;
	}

	/**
	 * @return the diskWriteCnt
	 */
	public long getDiskWriteCnt() {
		return diskWriteCnt;
	}

	/**
	 * @return the diskSeekCnt
	 */
	public long getDiskSeekCnt() {
		return diskSeekCnt;
	}
	
	/**
	 * @return the growDiskMark
	 */
	public int getGrowDiskMark() {
		return growDiskMark;
	}
	
	/**
	 * @return the checkPointMark
	 */
	public int getCheckPointMark() {
		return checkPointMark;
	}
	

	// /**
	// *
	// */
	// private final void mergeBuff(long[] buff, int len, File fpFile)
	// throws IOException {
	// File tmpFile = new File(this.filename + ".tmp");
	// tmpFile.delete();
	// BufferedRandomAccessFile fpRAF = new BufferedRandomAccessFile(fpFile,
	// "rw");
	// BufferedRandomAccessFile tmpRAF = new BufferedRandomAccessFile(tmpFile,
	// "rw");
	// int i = 0;
	// long value = 0L;
	// try {
	// value = fpRAF.readLong();
	// while (i < len) {
	// if (value < buff[i]) {
	// tmpRAF.writeLong(value);
	// value = fpRAF.readLong();
	// }
	// else {
	// tmpRAF.writeLong(buff[i++]);
	// }
	// }
	// } catch (EOFException e) { /*SKIP*/ }
	//
	// if (i < len) {
	// for (int j = i; j < len; j++) {
	// tmpRAF.writeLong(buff[j]);
	// }
	// }
	// else {
	// try {
	// do {
	// tmpRAF.writeLong(value);
	// value = fpRAF.readLong();
	// } while (true);
	// } catch (EOFException e) { /*SKIP*/ }
	// }
	//
	// fpRAF.close();
	// tmpRAF.close();
	// fpFile.delete();
	// tmpFile.renameTo(fpFile);
	// }

}
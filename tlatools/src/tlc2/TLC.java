// Copyright (c) 2003 Compaq Corporation.  All rights reserved.
// Portions Copyright (c) 2003 Microsoft Corporation.  All rights reserved.
// Last modified on Thu 10 April 2008 at 14:31:23 PST by lamport 
//      modified on Wed Dec  5 22:37:20 PST 2001 by yuanyu 

package tlc2;

import tla2sany.modanalyzer.SpecObj;
import tlc2.tool.AbstractChecker;
import tlc2.tool.Cancelable;
import tlc2.tool.DFIDModelChecker;
import tlc2.tool.ModelChecker;
import tlc2.tool.Simulator;
import tlc2.util.RandomGenerator;
import tlc2.value.Value;
import util.Assert;
import util.FP64;
import util.FileUtil;
import util.FilenameToStream;
import util.SimpleFilenameToStream;
import util.ToolIO;
import util.UniqueString;

/**
 * Main TLC starter class
 * @author Yuan Yu
 * @author Leslie Lamport
 * @author Simon Zambrovski
 * @version $Id$
 */
public class TLC
{

    // SZ Feb 20, 2009: the class has been 
    // transformed from static to dynamic
    private boolean isSimulate; 
    private boolean cleanup;
    private boolean deadlock;

    private boolean noSeed;
    private long seed;
    private long aril;

    private String mainFile;
    private String configFile;
    private String dumpFile;
    private String fromChkpt;

    private int fpIndex;
    private int traceDepth;
    private FilenameToStream resolver;
    private SpecObj specObj;
    
    // handle to the cancellable instance (MC or Simulator)
    private Cancelable instance;

    /**
     * Initialization
     */
    public TLC()
    {
        
        isSimulate = false; // Default to model checking
        cleanup = false;
        deadlock = true;
        
        noSeed = true;
        seed = 0;
        aril = 0;
        
        mainFile = null;
        configFile = null;
        dumpFile = null;
        fromChkpt = null;
        resolver = null;

        fpIndex = 0;
        traceDepth = 100;
        
        // instance is not set
        instance = null;
    }

    /*
     * This TLA checker (TLC) provides the following functionalities:
     *  1. Simulation of TLA+ specs: java tlc2.TLC -simulate spec[.tla]
     *  2. Model checking of TLA+ specs: java tlc2.TLC [-modelcheck] spec[.tla]
     *
     * The command line also provides the following options:
     *  o -config file: provide the config file.
     *    Defaults to spec.cfg if not provided
     *  o -deadlock: do not check for deadlock.
     *    Defaults to checking deadlock if not specified
     *  o -depth num: specify the depth of random simulation 
     *    Defaults to 100 if not specified
     *  o -seed num: provide the seed for random simulation
     *    Defaults to a random seed if not specified
     *  o -aril num: Adjust the seed for random simulation
     *    Defaults to 0 if not specified
     *  o -recover path: recover from the checkpoint at path
     *    Defaults to scratch run if not specified
     *  o -metadir path: store metadata in the directory at path
     *    Defaults to specdir/states if not specified
     *  o -workers num: the number of TLC worker threads
     *    Defaults to 1
     *  o -dfid num: use depth-first iterative deepening with initial depth num
     *  o -cleanup: clean up the states directory
     *  o -dump file: dump all the states into file
     *  o -difftrace: when printing trace, show only
     *                the differences between successive states
     *    Defaults to printing full state descriptions if not specified
     *    (Added by Rajeev Joshi)
     *  o -terse: do not expand values in Print statement
     *    Defaults to expand value if not specified
     *  o -coverage minutes: collect coverage information on the spec,
     *                       print out the information every minutes.
     *    Defaults to no coverage if not specified
     *  o -continue: continue running even when invariant is violated
     *    Defaults to stop at the first violation if not specified
     *  o -nowarning: disable all the warnings
     *    Defaults to report warnings if not specified
     *  o -fp num: use the num'th irreducible polynomial from the list
     *    stored in the class FP64.
     *  o -view: apply VIEW (if provided) when printing out states.
     *  o -gzip: control if gzip is applied to value input/output stream.
     *    Defaults to use gzip.
     */
    public static void main(String[] args)
    {
        System.out.println("TLC2 " + TLCGlobals.versionOfTLC);
        TLC tlc = new TLC();

        // handle parameters
        if (tlc.handleParameters(args))
        {
            tlc.setResolver(new SimpleFilenameToStream());
            // call the actual processing method
            tlc.process();
        }
        // terminate
        System.exit(0);
    }

    /**
     * This method handles parameter arguments and prepares the actual call
     * <strong>Note:</strong> This method set ups the static TLCGlobals variables
     * @return status of parsing: true iff parameter check was ok, false otherwise
     */
    // SZ Feb 23, 2009: added return status to indicate the error in parsing
    public boolean handleParameters(String[] args)
    {
        // SZ Feb 20, 2009: extracted this method to separate the 
        // parameter handling from the actual processing
        int index = 0;
        while (index < args.length)
        {
            if (args[index].equals("-simulate"))
            {
                isSimulate = true;
                index++;
            } else if (args[index].equals("-modelcheck"))
            {
                isSimulate = false;
                index++;
            } else if (args[index].equals("-config"))
            {
                index++;
                if (index < args.length)
                {
                    configFile = args[index];
                    int len = configFile.length();
                    if (configFile.startsWith(".cfg", len - 4))
                    {
                        configFile = configFile.substring(0, len - 4);
                    }
                    index++;
                } else
                {
                    printErrorMsg("Error: expect a file name for -config option.");
                    return false;
                }
            } else if (args[index].equals("-difftrace"))
            {
                index++;
                TLCGlobals.printDiffsOnly = true;
            } else if (args[index].equals("-deadlock"))
            {
                index++;
                deadlock = false;
            } else if (args[index].equals("-cleanup"))
            {
                index++;
                cleanup = true;
            } else if (args[index].equals("-nowarning"))
            {
                index++;
                TLCGlobals.warn = false;
            } else if (args[index].equals("-gzip"))
            {
                index++;
                TLCGlobals.useGZIP = false;
            } else if (args[index].equals("-dump"))
            {
                index++;
                if (index < args.length)
                {
                    dumpFile = args[index];
                    int len = dumpFile.length();
                    if (!(dumpFile.startsWith(".dump", len - 5)))
                    {
                        dumpFile = dumpFile + ".dump";
                    }
                    index++;
                } else
                {
                    printErrorMsg("Error: A file name for dumping states required.");
                    return false;
                }
            } else if (args[index].equals("-depth"))
            {
                index++;
                if (index < args.length)
                {
                    try
                    {
                        traceDepth = Integer.parseInt(args[index]);
                        index++;
                    } catch (Exception e)
                    {
                        printErrorMsg("Error: An integer for trace length required. But encountered " + args[index]);
                        return false;
                    }
                } else
                {
                    printErrorMsg("Error: trace length required.");
                    return false;
                }
            } else if (args[index].equals("-seed"))
            {
                index++;
                if (index < args.length)
                {
                    try
                    {
                        seed = Long.parseLong(args[index]);
                        index++;
                        noSeed = false;
                    } catch (Exception e)
                    {
                        printErrorMsg("Error: An integer for seed required. But encountered " + args[index]);
                        return false;
                    }
                } else
                {
                    printErrorMsg("Error: seed required.");
                    return false;
                }
            } else if (args[index].equals("-aril"))
            {
                index++;
                if (index < args.length)
                {
                    try
                    {
                        aril = Long.parseLong(args[index]);
                        index++;
                    } catch (Exception e)
                    {
                        printErrorMsg("Error: An integer for aril required. But encountered " + args[index]);
                        return false;
                    }
                } else
                {
                    printErrorMsg("Error: aril required.");
                    return false;
                }
            } else if (args[index].equals("-recover"))
            {
                index++;
                if (index < args.length)
                {
                    fromChkpt = args[index++] + FileUtil.separator;
                } else
                {
                    printErrorMsg("Error: need to specify the metadata directory for recovery.");
                    return false;
                }
            } else if (args[index].equals("-metadir"))
            {
                index++;
                if (index < args.length)
                {
                    TLCGlobals.metaDir = args[index++] + FileUtil.separator;
                } else
                {
                    printErrorMsg("Error: need to specify the metadata directory.");
                    return false;
                }
            } else if (args[index].equals("-workers"))
            {
                index++;
                if (index < args.length)
                {
                    try
                    {
                        int num = Integer.parseInt(args[index]);
                        if (num < 1)
                        {
                            printErrorMsg("Error: at least one worker required.");
                            return false;
                        }
                        TLCGlobals.setNumWorkers(num);
                        index++;
                    } catch (Exception e)
                    {
                        printErrorMsg("Error: worker number required. But encountered " + args[index]);
                        return false;
                    }
                } else
                {
                    printErrorMsg("Error: expect an integer for -workers option.");
                    return false;
                }
            } else if (args[index].equals("-dfid"))
            {
                index++;
                if (index < args.length)
                {
                    try
                    {
                        TLCGlobals.DFIDMax = Integer.parseInt(args[index]);
                        if (TLCGlobals.DFIDMax < 0)
                        {
                            printErrorMsg("Error: expect a nonnegative integer for -dfid option.");
                            return false;
                        }
                        index++;
                    } catch (Exception e)
                    {
                        printErrorMsg("Errorexpect a nonnegative integer for -dfid option. " + "But encountered "
                                + args[index]);
                        return false;
                    }
                } else
                {
                    printErrorMsg("Error: expect a nonnegative integer for -dfid option.");
                    return false;
                }
            } else if (args[index].equals("-terse"))
            {
                index++;
                Value.expand = false;
            } else if (args[index].equals("-coverage"))
            {
                index++;
                if (index < args.length)
                {
                    try
                    {
                        TLCGlobals.coverageInterval = (int) (Float.parseFloat(args[index]) * 60000);
                        if (TLCGlobals.coverageInterval < 0)
                        {
                            printErrorMsg("Error: expect a nonnegative integer for -coverage option.");
                            return false;
                        }
                        index++;
                    } catch (NumberFormatException e)
                    {
                        
                        printErrorMsg("Error: An integer for coverage report interval required." + " But encountered "
                                + args[index]);
                        return false;
                    }
                } else
                {
                    printErrorMsg("Error: coverage report interval required.");
                    return false;
                }
            } else if (args[index].equals("-continue"))
            {
                index++;
                TLCGlobals.continuation = true;
            } else if (args[index].equals("-fp"))
            {
                index++;
                if (index < args.length)
                {
                    try
                    {
                        fpIndex = Integer.parseInt(args[index]);
                        if (fpIndex < 0 || fpIndex >= FP64.Polys.length)
                        {
                            printErrorMsg("Error: The number for -fp must be between 0 and " + (FP64.Polys.length - 1)
                                    + " (inclusive).");
                            return false;
                        }
                        index++;
                    } catch (Exception e)
                    {
                        printErrorMsg("Error: A number for -fp is required. But encountered " + args[index]);
                        return false;
                    }
                } else
                {
                    printErrorMsg("Error: expect an integer for -workers option.");
                    return false;
                }
            } else if (args[index].equals("-view"))
            {
                index++;
                TLCGlobals.useView = true;
            } else
            {
                if (args[index].charAt(0) == '-')
                {
                    printErrorMsg("Error: unrecognized option: " + args[index]);
                    return false;
                }
                if (mainFile != null)
                {
                    printErrorMsg("Error: more than one input files: " + mainFile + " and " + args[index]);
                    return false;
                }
                mainFile = args[index++];
                int len = mainFile.length();
                if (mainFile.startsWith(".tla", len - 4))
                {
                    mainFile = mainFile.substring(0, len - 4);
                }
            }
        }
        if (mainFile == null)
        {
            printErrorMsg("Error: Missing input TLA+ module.");
            return false;
        }
        if (configFile == null)
        {
            configFile = mainFile;
        }
        return true;
    }
    
    /**
     * The processing method
     */
    public void process()
    {
        // SZ Feb 20, 2009: extracted this method to separate the 
        // parameter handling from the actual processing
        
        try
        {
            // Initialize:
            if (fromChkpt != null)
            {
                // We must recover the intern var table as early as possible
                UniqueString.internTbl.recover(fromChkpt);
            }
            if (cleanup && fromChkpt == null)
            {
                // clean up the states directory only when not recovering
                FileUtil.deleteDir(TLCGlobals.metaRoot, true);
            }
            FP64.Init(fpIndex);

            // Start checking:
            if (isSimulate)
            {
                // random simulation
                RandomGenerator rng = new RandomGenerator();
                if (noSeed)
                {
                    seed = rng.nextLong();
                    rng.setSeed(seed);
                } else
                {
                    rng.setSeed(seed, aril);
                }
                ToolIO.out.println("Running Random Simulation with seed " + seed + ".");
                Simulator simulator = new Simulator(mainFile, configFile, null, deadlock, traceDepth, 
                        Long.MAX_VALUE, rng, seed, true, resolver, specObj);
                instance = simulator;
                simulator.simulate();
            } else
            {
                // model checking
                ToolIO.out.println("Model-checking");
                
                AbstractChecker mc = null;
                if (TLCGlobals.DFIDMax == -1)
                {
                    mc = new ModelChecker(mainFile, configFile, dumpFile, deadlock, fromChkpt, resolver, specObj);
                    TLCGlobals.mainChecker = (ModelChecker) mc;
                } else
                {
                    mc = new DFIDModelChecker(mainFile, configFile, dumpFile, deadlock, fromChkpt, true, resolver, specObj);
                }
                
                instance = mc;
                mc.modelCheck();
            }
        } catch (Throwable e)
        {
            System.gc();
            // Uncommented by LL on 2 July 2007
            Assert.printStack(e);
            if (e instanceof StackOverflowError)
            {
                ToolIO.err.println("Error: This was a Java StackOverflowError. It was probably the result\n"
                        + "of an incorrect recursive function definition that caused TLC to enter\n"
                        + "an infinite loop when trying to compute the function or its application\n"
                        + "to an element in its putative domain.");
            } else if (e instanceof OutOfMemoryError)
            {
                ToolIO.err.println("Error: Java ran out of memory.  Running Java with a larger memory allocation\n"
                        + "pool (heap) may fix this.  But it won't help if some state has an enormous\n"
                        + "number of successor states, or if TLC must compute the value of a huge set.");
            } else
            {
                ToolIO.err.println("Error: " + e.getMessage());
            }
        } finally 
        {
            
        }
    }

    /**
     * Sets resolver for the file names
     * @param resolver a resolver for the names, if <code>null</code> is used, 
     * the standard resolver {@link SimpleFilenameToStream} is used
     */
    public void setResolver(FilenameToStream resolver)
    {
        this.resolver = resolver;
        ToolIO.setDefaultResolver(resolver);
    }

    /**
     * Set external specification object
     * @param specObj spec object created external SANY run
     */
    public void setSpecObject(SpecObj specObj) 
    {
        this.specObj = specObj;
    }

    /**
     * Delegate cancellation request to the instance
     * @param flag
     */
    public void setCanceledFlag(boolean flag)
    {
        if (this.instance != null) 
        {
            this.instance.setCancelFlag(flag);
            System.out.println("Cancel flag set to " + flag);
        }
    }
    
    /**
     * Print out an error message, with usage hint
     * @param msg, message to print
     */
    private static void printErrorMsg(String msg)
    {
        ToolIO.err.println(msg);
        ToolIO.err.println("Usage: java tlc2.TLC [-option] inputfile");
    }
}

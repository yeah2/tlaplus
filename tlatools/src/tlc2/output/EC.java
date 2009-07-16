package tlc2.output;

import tla2sany.output.SANYCodes;

/**
 * Interface containing the error code constants 
 * @author Simon Zambrovski
 * @version $Id$
 */
public interface EC extends SANYCodes
{
    // Check and CheckImpl
    // check if the TLC option is the same for params
    public static final int CHECK_FAILED_TO_CHECK = 3000;
    public static final int CHECK_COULD_NOT_READ_TRACE = 3001;
    public static final int CHECK_PARSING_FAILED = 3002;
    
    public static final int CHECK_PARAM_EXPECT_CONFIG_FILENAME = 3100;
    public static final int CHECK_PARAM_USAGE = 3101;
    public static final int CHECK_PARAM_MISSING_TLA_MODULE = 3102;
    public static final int CHECK_PARAM_NEED_TO_SPECIFY_CONFIG_DIR = 3103;
    public static final int CHECK_PARAM_WORKER_NUMBER_REQUIRED = 3104;
    public static final int CHECK_PARAM_WORKER_NUMBER_TOO_SMALL = 3105;
    public static final int CHECK_PARAM_WORKER_NUMBER_REQUIRED2 = 3106;
    public static final int CHECK_PARAM_DEPTH_REQUIRED = 3107;
    public static final int CHECK_PARAM_DEPTH_REQUIRED2 = 3108;
    public static final int CHECK_PARAM_TRACE_REQUIRED = 3109;
    public static final int CHECK_PARAM_COVREAGE_REQUIRED = 3110;
    public static final int CHECK_PARAM_COVREAGE_REQUIRED2 = 3111;
    public static final int CHECK_PARAM_COVREAGE_TOO_SMALL = 3112;
    public static final int CHECK_PARAM_UNRECOGNIZED = 3113;
    public static final int CHECK_PARAM_TOO_MANY_INPUT_FILES = 3114;

    
    
    /*
     * TODO remove all these
     */
    public static final int UNKNOWN = -1;
    public final static int UNIT_TEST = -123456;
    
    public static final int GENERAL = 1000;
    public static final int SYSTEM_OUT_OF_MEMORY = 1001;
    public static final int SYSTEM_OUT_OF_MEMORY_TOO_MANY_INIT = 1002;
    public static final int SYSTEM_STACK_OVERFLOW = 1005;

    public static final int WRONG_COMMANDLINE_PARAMS_SIMULATOR = 1101;
    public static final int WRONG_COMMANDLINE_PARAMS_TLC = 1102;
    
    public static final int TLC_PP_PARSING_VALUE = 2000;
    public static final int TLC_PP_FORMATING_VALUE = 2001;
    
    public static final int TLC_METADIR_EXISTS = 2100;
    public static final int TLC_METADIR_CAN_NOT_BE_CREATED = 2101;
    public static final int TLC_INITIAL_STATE = 2102;
    public static final int TLC_NESTED_EXPRESSION = 2103;
    public static final int TLC_ASSUMPTION_FALSE = 2104;
    public static final int TLC_ASSUMPTION_EVALUATION_ERROR = 2105;
    public static final int TLC_STATE_NOT_COMPLETELY_SPECIFIED_INITIAL = 2106;

    public static final int TLC_INVARIANT_VIOLATED_INITIAL = 2107;
    public static final int TLC_PROPERTY_VIOLATED_INITIAL = 2108;
    public static final int TLC_STATE_NOT_COMPLETELY_SPECIFIED_NEXT = 2109;
    public static final int TLC_INVARIANT_VIOLATED_BEHAVIOR = 2110;
    public static final int TLC_INVARIANT_EVALUATION_FAILED = 2111;
    public static final int TLC_ACTION_PROPERTY_VIOLATED_BEHAVIOR = 2112;
    public static final int TLC_ACTION_PROPERTY_EVALUATION_FAILED = 2113;
    public static final int TLC_DEADLOCK_REACHED = 2114;
    
    public static final int TLC_TEMPORAL_PROPERTY_VIOLATED = 2116;
    public static final int TLC_FAILED_TO_RECOVER_NEXT = 2117;
    public static final int TLC_NO_STATES_SATISFYING_INIT = 2118;
    public static final int TLC_STRING_MODULE_NOT_FOUND = 2119;
    
    public static final int TLC_ERROR_STATE = 2120;
    public static final int TLC_BEHAVIOR_UP_TO_THIS_POINT = 2121;
    public static final int TLC_BACK_TO_STATE = 2122;
    public static final int TLC_FAILED_TO_RECOVER_INIT = 2123;
    public static final int TLC_REPORTER_DIED = 2124;

    public static final int SYSTEM_ERROR_READING_POOL = 2125;
    public static final int SYSTEM_CHECKPOINT_RECOVERY_CORRUPT = 2126;
    public static final int SYSTEM_ERROR_WRITING_POOL = 2127;
    public static final int SYSTEM_INDEX_ERROR = 2134;
    public static final int SYSTEM_STREAM_EMPTY = 2135;
    public static final int SYSTEM_FILE_NULL = 2137;
    public static final int SYSTEM_INTERRUPTED = 2138;
    public static final int SYSTEM_UNABLE_NOT_RENAME_FILE = 2160;
    public static final int SYSTEM_DISK_IO_ERROR_FOR_FILE = 2161;
    public static final int SYSTEM_METADIR_EXISTS = 2162;
    public static final int SYSTEM_METADIR_CREATION_ERROR = 2163;
    public static final int SYSTEM_UNABLE_TO_OPEN_FILE = 2167;
    /*
     * Bad description
     */
    public static final int TLC_BUG = 2128;

    /*
     * refactor 
     */
    public static final int SYSTEM_DISKGRAPH_ACCESS = 2129;
    
    public static final int TLC_AAAAAAA = 2130;
    public static final int TLC_REGISTRY_INIT_ERROR = 2131;
    public static final int TLC_CHOOSE_ARGUMENTS_WRONG = 2164;
    public static final int TLC_CHOOSE_UPPER_BOUND = 2165;
    
    
    public static final int TLC_VALUE_ASSERT_FAILED = 2132;
    public static final int TLC_VALUE_JAVA_METHOD_OVERRIDE = 2154;
    
    public static final int TLC_FP_NOT_IN_SET = 2133;
    public static final int TLC_FP_VALUE_ALREADY_ON_DISK = 2166;
    
    
    public static final int TLC_LIVE_BEGRAPH_FAILED_TO_CONSTRUCT = 2159;
    /*
     * Check this message
     */
    public static final int TLC_PARAMETER_MUST_BE_POSTFIX = 2136;
    public static final int TLC_COULD_NOT_DETERMINE_SUBSCRIPT = 2139;
    public static final int TLC_SUBSCRIPT_CONTAIN_NO_STATE_VAR = 2140;
    public static final int TLC_WRONG_TUPLE_FIELD_NAME = 2141;
    public static final int TLC_WRONG_RECORD_FIELD_NAME = 2142;
    public static final int TLC_UNCHANGED_VARIABLE_CHANGED = 2143;
    public static final int TLC_EXCEPT_APPLIED_TO_UNKNOWN_FIELD = 2144;
    

    public static final int TLC_MODULE_TLCGET_UNDEFINED = 2145;
    /*
    public static final int TLC_MODULE_TLCGET_WRONG_ARGUMENT = 2146;
    public static final int TLC_MODULE_TLCSET_WRONG_ARGUMENT = 2147;
    public static final int TLC_MODULE_TLCADAD_WRONG_ARGUMENT = 2148;
    public static final int TLC_MODULE_TLCADAD_WRONG_ARGUMENT2 = 2149;
    public static final int TLC_MODULE_TLCSORTSEQ_WRONG_ARGUMENT = 2150;
    public static final int TLC_MODULE_TLCSORTSEQ_WRONG_ARGUMENT2 = 2151;
    public static final int TLC_MODULE_TLCSORTSEQ_WRONG_ARGUMENT3 = 2152;
    */
    public static final int TLC_MODULE_APPLYING_TO_NOT_FINITE_SET = 2153;
    public static final int TLC_MODULE_ATTEMPTED_TO_COMPARE = 2155;
    public static final int TLC_MODULE_ATTEMPTED_TO_CHECK_MEMBER = 2158;
    public static final int TLC_MODULE_TRANSITIVE_CLOSURE1 = 2156;
    public static final int TLC_MODULE_TRANSITIVE_CLOSURE2 = 2157;
    public static final int TLC_MODULE_APPLYING_FUNCTION_WITH_INIFINTE_DOMAIN = 2168;
    public static final int TLC_MODULE_ARGUMENT_ERROR = 2169;
    
    
    
    



    

    
    
}
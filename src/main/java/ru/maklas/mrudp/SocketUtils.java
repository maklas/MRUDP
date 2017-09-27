package ru.maklas.mrudp;

/**
 * Created by maklas on 11.09.2017.
 * Static fields and methods for RequestCode and ResponseCode processing
 */
public class SocketUtils {

    public static final int REQUEST_TYPE = 1;

    public static final int OK = 100;

    public static final int UNDEFINED_ERROR = -100;
    public static final int CONNECTION_REFUSED_ERROR = -101;
    public static final int BAD_REQUEST = -102;
    public static final int INTERNAL_SERVER_ERROR = -103;
    public static final int ACCESS_DENIED_ERROR = -104;

    public static boolean isResponseType(int code){
        return code == 3 || code == 100 || (code >= -104 && code <= -100);
    }

    public static boolean isAnErrorCode(int code){
        return code <= -100 && code >= -104;
    }

    public static String errorCodeToString(int errorCode){
        switch (errorCode){
            case -100:
                return "Undefined Error";
            case -101:
                return "Connection Refused";
            case -102:
                return "Bad Request";
            case -103:
                return "Internal Server Error";
            case -104:
                return "Access Denied";

            default:
                return "Unknown Error";
        }
    }

    public static boolean isRequestType(int type) {
        return type == REQUEST_TYPE;
    }
}

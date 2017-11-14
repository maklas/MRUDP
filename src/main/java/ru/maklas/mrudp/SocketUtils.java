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
    public static final int DISCARDED = 0;

    public static boolean isResponseType(int code){
        return code == 3 || code == 100 || (code >= -104 && code <= -100);
    }

    public static boolean isAnErrorCode(int code){
        return code <= -100 && code >= -104;
    }

    public static String errorCodeToString(int errorCode){
        switch (errorCode){
            case 0:
                return "Discarded";
            case 100 :
                return "Ok";
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


    public static int intFromByteArray(byte[] bytes, int startOffset) {
        return bytes[startOffset] << 24 | (bytes[1 + startOffset] & 0xFF) << 16 | (bytes[2 + startOffset] & 0xFF) << 8 | (bytes[3 + startOffset] & 0xFF);
    }

    public static void putInt(int value, byte[] target, int startOffset){
        target[    startOffset] = (byte) (value >>> 24);
        target[1 + startOffset] = (byte) (value >>> 16);
        target[2 + startOffset] = (byte) (value >>> 8);
        target[3 + startOffset] = (byte)  value;
    }
}

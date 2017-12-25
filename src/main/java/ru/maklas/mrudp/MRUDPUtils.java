package ru.maklas.mrudp;

class MRUDPUtils {
    
    static final byte reliableRequest = 1;
    static final byte reliableResponse = 2;
    static final byte unreliableRequest = 3;
    static final byte pingRequest = 4;
    static final byte pingResponse = 5;
    static final byte connectionRequest = 6;
    static final byte connectionResponseAccepted = 7;
    static final byte connectionResponseRejected = 8;
    static final byte connectionAcknowledgment = 9;
    static final byte connectionAcknowledgmentResponse = 10;
    static final byte disconnect = 11;


    private static final byte[] dcPacket = new byte[]{disconnect, 0, 0, 0, 0};

    static byte[] buildReliableRequest (int seq, byte [] data){
        return build5byte(reliableRequest, seq, data);
    }

    static void appendReliableRequest(int seq, byte[] dataWith5Offset){
        dataWith5Offset[0] = reliableRequest;
        putInt(dataWith5Offset, seq, 1);
    }

    static byte[] buildReliableResponse (int seq){
        return build5byte(reliableResponse, seq);
    }

    static byte[] buildUnreliableRequest (byte[] data){
        return build5byte(unreliableRequest, 0, data);
    }

    static byte[] buildPingRequest (int seq, long startTime){
        byte[] ret = new byte[13];
        ret[0] = pingRequest;
        putInt(ret, seq, 1);
        putLong(ret, startTime, 5);
        return ret;
    }

    static byte[] buildPingResponse (int seq, long startTime){
        byte[] ret = new byte[13];
        ret[0] = pingResponse;
        putInt(ret, seq, 1);
        putLong(ret, startTime, 5);
        return ret;
    }

    static byte[] buildConnectionRequest (int seq, int otherSeq, byte[] data){
        byte[] fullData = new byte[data.length + 9];
        fullData[0] = connectionRequest;
        putInt(fullData, seq, 1);
        putInt(fullData, otherSeq, 5);
        System.arraycopy(data, 0, fullData, 9, data.length);
        return fullData;
    }

    static byte[] buildConnectionResponse (boolean accepted, int seq, byte[] data){
        return build5byte(accepted ? connectionResponseAccepted : connectionResponseRejected, seq, data);
    }

    static byte[] buildConnectionAck (int seq){
        return build5byte(connectionAcknowledgment, seq);
    }

    static byte[] buildConnectionAckResponse(int seq){
        return build5byte(connectionAcknowledgmentResponse, seq);
    }

    static byte[] buildDisconnect (){
        return dcPacket;
    }

    static byte[] build5byte(byte settings, int seq, byte [] data){
        int dataLength = data.length;
        byte[] ret = new byte[dataLength + 5];
        ret[0] = settings;
        putInt(ret, seq, 1);
        System.arraycopy(data, 0, ret, 5, dataLength);
        return ret;
    }

    static byte[] build5byte(byte settings, int seq){
        byte[] ret = new byte[5];
        ret[0] = settings;
        putInt(ret, seq, 1);
        return ret;
    }

    static void putInt(byte[] bytes, int value, int offset) {
        bytes[    offset] = (byte) (value >>> 24);
        bytes[1 + offset] = (byte) (value >>> 16);
        bytes[2 + offset] = (byte) (value >>> 8);
        bytes[3 + offset] = (byte)  value;
    }

    static int extractInt(byte[] bytes, int offset){
        return
                bytes[offset] << 24             |
                        (bytes[1 + offset] & 0xFF) << 16 |
                        (bytes[2 + offset] & 0xFF) << 8  |
                        (bytes[3 + offset] & 0xFF);
    }

    static void putLong(byte[] bytes, long value, int offset) {
        bytes[    offset] = (byte) (value >>> 56);
        bytes[1 + offset] = (byte) (value >>> 48);
        bytes[2 + offset] = (byte) (value >>> 40);
        bytes[3 + offset] = (byte) (value >>> 32);
        bytes[4 + offset] = (byte) (value >>> 24);
        bytes[5 + offset] = (byte) (value >>> 16);
        bytes[6 + offset] = (byte) (value >>> 8);
        bytes[7 + offset] = (byte)  value;
    }

    static long extractLong(byte[] bytes, int offset){
        long result = 0;
        for (int i = offset; i < 8 + offset; i++) {
            result <<= 8;
            result |= (bytes[i] & 0xFF);
        }
        return result;
    }

    static void printSettings(byte settingsByte){
        System.out.println(getSettingsAsString(settingsByte));
    }

    static String getSettingsAsString(byte settingsBytes){
        switch (settingsBytes){
            case reliableRequest                 : return "ReliableRequest(" + reliableRequest + ")";
            case reliableResponse                : return "ReliableResponse(" + reliableResponse + ")";
            case unreliableRequest               : return "UnreliableRequest(" + unreliableRequest + ")";
            case pingRequest                     : return "PingRequest(" + pingRequest + ")";
            case pingResponse                    : return "PingResponse(" + pingResponse + ")";
            case connectionRequest               : return "ConnectionRequest(" + connectionRequest + ")";
            case connectionResponseAccepted      : return "ConnectionResponseAccepted(" + connectionResponseAccepted + ")";
            case connectionResponseRejected      : return "ConnectionResponseRejected(" + connectionResponseRejected + ")";
            case connectionAcknowledgment: return "ConnectionResponseAcknowledgment(" + connectionAcknowledgment + ")";
            case disconnect                      : return "Disconnect(" + disconnect + ")";
            default                              : return "UNKNOWN TYPE(" + settingsBytes + ")";
        }
    }

}

package ru.maklas.mrudp;

import java.util.ArrayList;

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
    static final byte batch = 12;
    static final byte unreliableBatch = 13;

    static final byte ntpRequest = 14;
    static final byte ntpResponse = 15;

    static byte[] buildReliableRequest (int seq, byte [] data){
        return build5byte(reliableRequest, seq, data);
    }

    static void appendReliableRequest(int seq, byte[] dataWith5Offset){
        dataWith5Offset[0] = reliableRequest;
        putInt(dataWith5Offset, seq, 1);
    }
    static byte[] appendUnreliableRequest(byte[] dataWith5Offset){
        dataWith5Offset[0] = unreliableRequest;
        return dataWith5Offset;
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

    static byte[] buildDisconnect (String msg){
        return build5byte(disconnect, 0, msg.getBytes());
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

    public static byte[] buildBatch(int seq, MRUDPBatch batch) {
        ArrayList<byte[]> array = batch.array;
        //5 - seq + bath type;
        //for each byte[] - size (2 bytes) + data;
        //
        int retSize = 6;
        int batchSize = array.size();
        for (int i = 0; i < batchSize; i++) {
            retSize += array.get(i).length + 2;
        }

        byte[] ret = new byte[retSize];
        ret[0] = MRUDPUtils.batch;
        putInt(ret, seq, 1);
        ret[5] = (byte) batchSize;
        int position = 6;
        for (int i = 0; i < batchSize; i++) {
            byte[] src = array.get(i);
            int srcLen = src.length;
            putShort(ret, srcLen, position);
            System.arraycopy(src, 0, ret, position + 2, srcLen);
            position += srcLen + 2;
        }
        return ret;
    }

    /**
     * @return (byte[] batchRequest, int currentPosition)
     */
    public static Object[] buildSafeBatch(final int seq, MRUDPBatch batch, final int pos, int bufferSize) {
        ArrayList<byte[]> array = batch.array;
        int retSize = 6;
        int batchSize = array.size();
        int endIIncluded = pos;
        for (int i = pos; i < batchSize; i++) {
            int length = array.get(i).length;
            if (retSize + length + 2> bufferSize){
                break;
            }
            endIIncluded = i;
            retSize += length + 2;
        }

        if (retSize == 6){
            throw new RuntimeException("Can't fit byte[] if length " + array.get(pos).length + " in bufferSize of length " + bufferSize + ". Make sure it's at least 8 bytes more than source byte[]");
        }

        int safeBatchSize = endIIncluded - pos + 1;
        byte[] ret = new byte[retSize];
        ret[0] = MRUDPUtils.batch;
        putInt(ret, seq, 1);
        ret[5] = (byte) safeBatchSize;
        int position = 6;
        for (int i = pos; i <= endIIncluded; i++) {
            byte[] src = array.get(i);
            int srcLen = src.length;
            putShort(ret, srcLen, position);
            System.arraycopy(src, 0, ret, position + 2, srcLen);
            position += srcLen + 2;
        }
        return new Object[]{ret, Integer.valueOf(endIIncluded + 1)};
    }

    public static byte[] buildNTPResponse(int seq, long t0, long t1){
        byte[] ret = new byte[1 + 4 + 8 + 8 + 8];

        ret[0] = ntpResponse;
        putInt(ret, seq, 1);
        putLong(ret, t0, 5);
        putLong(ret, t1, 13);
        putLong(ret, System.currentTimeMillis(), 21);
        return ret;
    }


    public static byte[] buildUnreliableBatch(MRUDPBatch batch) {
        ArrayList<byte[]> array = batch.array;
        //5 - seq + bath type;
        //for each byte[] - size (2 bytes) + data;
        //
        int retSize = 6;
        int batchSize = array.size();
        for (int i = 0; i < batchSize; i++) {
            retSize += array.get(i).length + 2;
        }

        byte[] ret = new byte[retSize];
        ret[0] = MRUDPUtils.unreliableBatch;
        ret[5] = (byte) batchSize;
        int position = 6;
        for (int i = 0; i < batchSize; i++) {
            byte[] src = array.get(i);
            int srcLen = src.length;
            putShort(ret, srcLen, position);
            System.arraycopy(src, 0, ret, position + 2, srcLen);
            position += srcLen + 2;
        }
        return ret;
    }



    /**
     * Assumes that batch data is correct. Otherwise will throw Runtime exceptions
     */
    public static byte[][] breakBatchDown(byte[] batchPacket){
        int arrSize = batchPacket[5];
        byte[][] ret = new byte[arrSize][];
        int pos = 6;
        for (int i = 0; i < arrSize; i++) {
            int packetSize = extractShort(batchPacket, pos);
            ret[i] = new byte[packetSize];
            System.arraycopy(batchPacket, pos + 2, ret[i], 0, packetSize);
            pos += packetSize + 2;
        }
        return ret;
    }

    static void putShort(byte[] bytes, int value, int offset){
        bytes[    offset] = (byte) (value >>> 8);
        bytes[1 + offset] = (byte)  value;
    }

    static int extractShort(byte[] bytes, int offset){
        return
                bytes[offset] << 8             |
                        (bytes[1 + offset] & 0xFF);
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
            case connectionAcknowledgment        : return "ConnectionResponseAcknowledgment(" + connectionAcknowledgment + ")";
            case disconnect                      : return "Disconnect(" + disconnect + ")";
            case batch                           : return "Batch(" + batch + ")";
            case unreliableBatch                 : return "UnreliableBatch(" + unreliableBatch + ")";
            default                              : return "UNKNOWN TYPE(" + settingsBytes + ")";
        }
    }
}

package ru.maklas.mrudp.impl;

/**
 * Created by amaklakov on 03.11.2017.
 */
public class ResponsePackage {

    public enum Type {Ok, Error, Discarded}

    private Type type;
    private byte[] data;
    private boolean internalDiscard;
    private int responseCode;
    private int sequenceNumber = 0;

    public ResponsePackage(Type type, int responseCode, byte[] data, int sequenceNumber) {
        this.type = type;
        this.data = data;
        this.responseCode = responseCode;
        this.sequenceNumber = sequenceNumber;
    }

    public ResponsePackage(Type type, boolean internalDiscard, int responseCode, int sequenceNumber) {
        this.type = type;
        this.internalDiscard = internalDiscard;
        this.sequenceNumber = sequenceNumber;
        this.data = new byte[0];
        this.responseCode = responseCode;
    }

    public Type getType() {
        return type;
    }

    public byte[] getData() {
        return data;
    }

    public String getDataAsString(){
        return new String(data);
    }

    public int getResponseCode() {
        return responseCode;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public boolean isInternalDiscard() {
        return internalDiscard;
    }

    @Override
    public String toString() {
        return "ResponsePackage{" +
                "sequenceNumber=" + sequenceNumber +
                ", type=" + type +
                ", code=" + responseCode +
                (data.length == 0 ? "":", data=" + new String(data)) +
                '}';
    }
}

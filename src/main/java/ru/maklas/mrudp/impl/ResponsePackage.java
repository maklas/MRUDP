package ru.maklas.mrudp.impl;

/**
 * Created by amaklakov on 03.11.2017.
 */
public class ResponsePackage {

    public enum Type {Ok, Error, Discarded}

    private Type type;
    private byte[] data;
    private int responseCode;

    public ResponsePackage(Type type, int responseCode, byte[] data) {
        this.type = type;
        this.data = data;
        this.responseCode = responseCode;
    }

    public ResponsePackage(Type type, int responseCode) {
        this.type = type;
        this.data = new byte[0];
        this.responseCode = responseCode;
    }

    public Type getType() {
        return type;
    }

    public byte[] getData() {
        return data;
    }

    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public String toString() {
        return "ResponsePackage{" +
                "type=" + type +
                ", code=" + responseCode +
                (data.length == 0 ? "":", data=" + new String(data)) +
                '}';
    }
}

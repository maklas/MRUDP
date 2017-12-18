package ru.maklas.mrudp;

import java.util.Arrays;

public class ConnectionResponse {

    public enum Type {ALREADY_CONNECTED_OR_CONNECTING, NO_RESPONSE, ACCEPTED, NOT_ACCEPTED}

    private final Type type;
    private final byte[] responseData;

    public ConnectionResponse(Type type, byte[] responseData) {
        this.type = type;
        this.responseData = responseData;
    }

    public Type getType() {
        return type;
    }

    public byte[] getResponseData() {
        return responseData;
    }

    @Override
    public String toString() {
        return "ConnectionResponse{" +
                "result=" + type +
                ", responseData=" + Arrays.toString(responseData) +
                '}';
    }
}

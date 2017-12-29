package ru.maklas.mrudp;

import java.util.Arrays;

/**
 * Class that represents Response from a server. Might contain
 * <li>Accept of connection with response data</li>
 * <li>Error e.g. if you're already connected</li>
 * <li>Connection timeout</li>
 * <li>Connection refuse e.g. Server didn't accept your password or whatever</li>
 */
public class ConnectionResponse {

    /**
     * Type of response. If type is {@link Type#ACCEPTED} then socket is considered to be connected
     * and you can expect to receive data. Nothing happens in any other cases
     */
    public enum Type {ALREADY_CONNECTED_OR_CONNECTING, NO_RESPONSE, ACCEPTED, NOT_ACCEPTED}

    private final Type type;
    private final byte[] responseData;

    public ConnectionResponse(Type type, byte[] responseData) {
        this.type = type;
        this.responseData = responseData;
    }

    /**
     * Type of response. If type is {@link Type#ACCEPTED} then socket is considered to be connected
     * and you can expect to receive data. Nothing happens in any other cases
     */
    public Type getType() {
        return type;
    }

    /**
     * Response data that server responded with. most likely not null if type is ACCEPTED or NOT_ACCEPTED
     */
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

    @Override
    public boolean equals(Object event) {
        if (this == event) return true;
        if (event == null || getClass() != event.getClass()) return false;

        ConnectionResponse that = (ConnectionResponse) event;

        if (type != that.type) return false;
        return Arrays.equals(responseData, that.responseData);
    }
}

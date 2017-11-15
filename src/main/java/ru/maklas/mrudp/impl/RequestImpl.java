package ru.maklas.mrudp.impl;


import ru.maklas.mrudp.RequestWriter;

import java.net.InetAddress;

/**
 * Created by maklas on 11.09.2017.
 * <p>Request Implementation</p>
 */
public class RequestImpl implements RequestWriter {

    private final int sequence;
    private final InetAddress address;
    private final int port;
    private final byte[] data;
    private final boolean responseRequired;
    private int timesRequested;
    private int discardTime;
    private boolean hasAlreadyBeenSend;

    public RequestImpl(int sequence, InetAddress address, int port, byte[] data,
                       boolean responseRequired, boolean hasAlreadyBeenSend, int discardTime) {
        this.sequence = sequence;
        this.address = address;
        this.port = port;
        this.data = data;
        this.responseRequired = responseRequired;
        this.hasAlreadyBeenSend = hasAlreadyBeenSend;
        this.discardTime = discardTime;
        this.timesRequested = 0;
    }

    @Override
    public int getSequenceNumber() {
        return sequence;
    }

    @Override
    public InetAddress getAddress() {
        return address;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public byte[] getData() {
        return data;
    }

    @Override
    public String getDataAsString() {
        return new String(data);
    }

    @Override
    public String toString() {
        return "Request{" +
                "seq=" + sequence +
                ", address=" + address +
                ":" + port +
                ", data=" + (data == null ? "NO_DATA" : getDataAsString()) +
                '}';
    }

    @Override
    public int getTimesRequested() {
        return timesRequested;
    }

    @Override
    public boolean responseRequired() {
        return responseRequired;
    }

    @Override
    public void incTimesRequested() {
        timesRequested++;
    }

    @Override
    public void setTimesRequested(int times) {
        timesRequested = times;
    }

    @Override
    public int getDiscardTime() {
        return discardTime;
    }

    @Override
    public boolean hasAlreadyBeenSend() {
        return hasAlreadyBeenSend;
    }
}

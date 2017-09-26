package ru.maklas.mrudp.impl;


import ru.maklas.mrudp.RequestWriter;

import java.net.InetAddress;

/**
 * Created by maklas on 11.09.2017.
 * <p>Имплиментация реквеста</p>
 */
public class RequestImpl implements RequestWriter {

    private final int sequence;
    private final InetAddress address;
    private final int port;
    private final byte[] data;
    private final boolean responseRequired;
    private int timesRequested;

    public RequestImpl(int sequence, InetAddress address, int port, byte[] data, boolean responseRequired) {
        this.sequence = sequence;
        this.address = address;
        this.port = port;
        this.data = data;
        this.responseRequired = responseRequired;
        timesRequested = 0;
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
                "address=" + address +
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
}

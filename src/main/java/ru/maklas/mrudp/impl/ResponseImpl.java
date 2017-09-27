package ru.maklas.mrudp.impl;


import ru.maklas.mrudp.Response;

import java.net.InetAddress;

/**
 * Created by maklas on 11.09.2017.
 * <p>Implementation for Response</p>
 */
public class ResponseImpl implements Response {

    protected final InetAddress address;
    protected final int port;
    protected final int seq;
    protected byte[] data;
    protected int responseCode;


    public ResponseImpl(int sequenceNumber, InetAddress address, int port, int responseCode, byte[] data) {
        this.address = address;
        this.port = port;
        this.responseCode = responseCode;
        this.seq = sequenceNumber;
        this.data = data;
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
    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public int getSequenceNumber() {
        return seq;
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
    public String toString() {
        return "ResponseImpl{" +
                "address=" + address +
                ":" + port +
                ", seq=" + seq +
                ", data=" + (data == null? "NO_DATA": new String(data))+
                ", rCode=" + responseCode +
                '}';
    }
}

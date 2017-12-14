package ru.maklas.locator;

import java.net.InetAddress;

public class LocatorResponse {

    private final InetAddress address;
    private final int port;
    private final byte[] response;

    public LocatorResponse(InetAddress address, int port, byte[] response) {
        this.address = address;
        this.port = port;
        this.response = response;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public byte[] getResponse() {
        return response;
    }
}

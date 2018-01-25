package ru.maklas.locator;

import java.net.InetAddress;
import java.util.Arrays;

public class LocatorResponse {

    private final InetAddress address;
    private final int port;
    private final byte[] response;

    LocatorResponse(InetAddress address, int port, byte[] response) {
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


    @Override
    public String toString() {
        return "LocatorResponse{" +
                "address=" + address.getHostAddress() + ':' + port +
                ", response=" + Arrays.toString(response) +
                '}';
    }
}

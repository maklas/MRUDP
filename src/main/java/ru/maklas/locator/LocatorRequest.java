package ru.maklas.locator;

public class LocatorRequest {

    int seq;
    byte[] request;

    public LocatorRequest(int seq, byte[] request) {
        this.seq = seq;
        this.request = request;
    }
}

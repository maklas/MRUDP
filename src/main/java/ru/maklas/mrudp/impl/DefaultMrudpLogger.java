package ru.maklas.mrudp.impl;

import ru.maklas.mrudp.MrudpLogger;
import ru.maklas.mrudp.Request;

public class DefaultMrudpLogger implements MrudpLogger {

    @Override
    public void log(String msg) {
        System.err.println(msg);
    }

    @Override
    public void log(Exception e) {
        e.printStackTrace();
    }

    @Override
    public void logRetry(Request request) {
        System.err.println("Retry for: " + request + " #" + request.getTimesRequested());
    }

    @Override
    public void logQuitting() {
        log("Exiting");
    }

    @Override
    public void logResponseWithoutRequest(byte[] fullData) {
        log("Got response without Request: data=" + new String(fullData));
    }
}

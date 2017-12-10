package ru.maklas.mrudp;

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
    public void logQuitting() {
        log("Exiting");
    }

    @Override
    public void logResponseWithoutRequest(byte[] fullData) {
        log("Got response without Request: data=" + new String(fullData));
    }
}

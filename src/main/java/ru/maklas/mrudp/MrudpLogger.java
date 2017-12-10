package ru.maklas.mrudp;

/**
 * Created by maklas on 26.09.2017.
 * <p>Logger for notifying about inside processes of MRUDPSocket</p>
 */
public interface MrudpLogger {

    void log(String msg);

    void log(Exception e);

    void logQuitting();

    void logResponseWithoutRequest(byte[] fullData);
}

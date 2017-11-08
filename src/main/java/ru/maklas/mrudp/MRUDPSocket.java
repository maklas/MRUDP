package ru.maklas.mrudp;

import ru.maklas.mrudp.impl.FutureResponse;

import java.net.InetAddress;

/**
 * Created by maklas on 11.09.2017.
 * <p>Socket through which data can be sent to other MRUDPSocket
 * It uses UDP protocol.
 * Always close socket using killConnection() to shutdown socket listening and kill associated threads</p>
 */
public interface MRUDPSocket {

    void setProcessor(RequestProcessor listener);

    void sendRequest(InetAddress address, int port, byte[] data, int discardTime, ResponseHandler handler);

    void sendRequest(InetAddress address, int port, byte[] data);

    void sendRequest(InetAddress address, int port, String data, int discardTime, ResponseHandler handler);

    void sendRequest(InetAddress address, int port, String data);

    FutureResponse sendRequestGetFuture(InetAddress address, int port, int discardTime, byte[] data);

    FutureResponse sendRequestGetFuture(InetAddress address, int port, String data);

    void resendRequest(Request request, ResponseHandler handler);

    void killConnection();

    void setResponseTimeout(int ms);

    int getLocalPort();

    void setLogger(MrudpLogger logger);

}

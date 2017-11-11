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

    void setProcessor(RequestProcessor processor);

    void sendRequest(InetAddress address, int port, byte[] data, int responseTimeOut, ResponseHandler handler);

    void sendRequest(InetAddress address, int port, byte[] data);

    void sendRequest(InetAddress address, int port, String data, int responseTimeOut, ResponseHandler handler);

    void sendRequest(InetAddress address, int port, String data);

    FutureResponse sendRequestGetFuture(InetAddress address, int port, byte[] data, int discardTime, int resendTries);

    void resendRequest(Request request, ResponseHandler handler);

    void killConnection();

    int getLocalPort();

    void setLogger(MrudpLogger logger);

}

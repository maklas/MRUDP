package ru.maklas.mrudp;

import java.net.InetAddress;

/**
 * Created by maklas on 11.09.2017.
 * <p>Socket through which data can be sent to other MRUDPSocket
 * It uses UDP protocol.
 * Always close socket using killConnection() to shutdown socket listening and kill associated threads</p>
 */
public interface MRUDPSocket {

    void setProcessor(RequestProcessor listener);

    void setFilter(SocketFilter filter);

    void sendRequest(InetAddress address, int port, byte[] data, ResponseHandler handler);

    void sendRequest(InetAddress address, int port, byte[] data);

    void sendRequest(InetAddress address, int port, String data, ResponseHandler handler);

    void sendRequest(InetAddress address, int port, String data);

    void resendRequest(Request request, ResponseHandler handler);

    void killConnection();

    void setResponseTimeout(int ms);

    int getLocalPort();

    void setLogger(MrudpLogger logger);

}

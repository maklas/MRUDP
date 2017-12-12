package ru.maklas.mrudp;

import java.net.InetAddress;

public interface MRUDPSocket {

    enum SocketState {NOT_CONNECTED, CONNECTING, CONNECTED}

    // ACTIONS

    ConnectionResponse connect(int timeout, InetAddress address, int port, byte[] data);

    boolean send(byte[] data);

    boolean sendUnreliable(byte[] data);

    void start(final int updateThreadSleepTimeMS);

    void receive(SocketProcessor processor);

    void addListener(MRUDPListener listener);

    void removeListeners(MRUDPListener listener);

    void setPingUpdateTime(int ms);

    void setUserData(Object userData);

    int getCurrentSeq();

    boolean disconnect();

    void close();

    // GETTERS

    boolean isConnected();

    SocketState getState();

    InetAddress getRemoteAddress();

    int getRemotePort();

    int getLocalPort();

    boolean isProcessing();

    float getPing();

    Object getUserData();

}

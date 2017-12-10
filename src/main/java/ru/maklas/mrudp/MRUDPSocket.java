package ru.maklas.mrudp;

import java.net.InetAddress;

public interface MRUDPSocket {

    enum SocketState {NOT_CONNECTED, CONNECTING, CONNECTED}

    // ACTIONS

    ConnectionResponse connect(int timeout, InetAddress address, int port, byte[] data);

    boolean send(byte[] data);

    boolean sendUnreliable(byte[] data);

    void start(boolean startUpdateThread, final int updateThreadSleepTimeMS);

    void update();

    void receive(SocketProcessor processor);

    void close();

    void addListener(MRUDPListener listener);

    void removeListeners(MRUDPListener listener);

    void setPingUpdateTime(int ms);

    void setUserData(Object userData);


    // GETTERS

    boolean isConnected();

    SocketState getState();

    InetAddress getRemoteAddress();

    int getRemotePort();

    int getLocalPort();

    boolean isProcessing();

    int getPing();

    Object getUserData();

}

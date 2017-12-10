package ru.maklas.mrudp;

import java.net.InetAddress;

public interface MRUDPSocket2 {

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


    // GETTERS

    boolean isConnected();

    SocketState getState();

    InetAddress getRemoteAddress();

    int getRemotePort();

    int getLocalPort();

    boolean isProcessing();

}
package ru.maklas.mrudp;

import java.net.InetAddress;

public interface ServerModel {

    byte[] validateNewConnection(InetAddress address, int port, byte[] userData);

    void registerNewConnection(FixedBufferMRUDP2 socket);

    void handleUnknownSourceMsg(byte[] userData);

    void onSocketDisconnected(FixedBufferMRUDP2 socket);

}

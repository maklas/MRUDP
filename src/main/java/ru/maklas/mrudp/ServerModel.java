package ru.maklas.mrudp;

import java.net.InetAddress;

public interface ServerModel {

    ConnectionResponsePackage<byte[]> validateNewConnection(InetAddress address, int port, byte[] userData);

    void registerNewConnection(MRUDPSocketImpl socket, ConnectionResponsePackage<byte[]> responsePackage);

    void handleUnknownSourceMsg(byte[] userData);

    void onSocketDisconnected(MRUDPSocketImpl socket);

}

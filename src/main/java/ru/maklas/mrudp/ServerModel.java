package ru.maklas.mrudp;

import java.net.InetAddress;

public interface ServerModel {

    ConnectionResponsePackage<byte[]> validateNewConnection(InetAddress address, int port, byte[] userData);

    void registerNewConnection(MRUDPSocketImpl socket, ConnectionResponsePackage<byte[]> responsePackage, byte[] userData);

    void handleUnknownSourceMsg(byte[] userData);

    void onSocketDisconnected(MRUDPSocketImpl socket);

}

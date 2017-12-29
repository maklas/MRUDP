package ru.maklas.mrudp;

import java.net.InetAddress;

/**
 * Server model. Represents server logging in.
 */
public interface ServerModel {

    /**
     * Validates new connection based on user address, port and data that he sent with method {@link MRUDPSocket#connect(int, InetAddress, int, byte[])}
     * @return ConnectionResponsePackage that represents ither acceptance or refusal of connection
     */
    ConnectionResponsePackage<byte[]> validateNewConnection(InetAddress address, int port, byte[] userData);

    /**
     * Register new Socket in your system. At this phase you might add userData to it so you can track your data from sockets.
     * @param responsePackage response package that you responded to this socket in validation phase
     * @param userData Data that previously was in {@link #validateNewConnection(InetAddress, int, byte[])}
     */
    void registerNewConnection(MRUDPSocketImpl socket, ConnectionResponsePackage<byte[]> responsePackage, byte[] userData);

    /**
     * Message that was receved by not connected socket, but protocol looks like MRUDP. You have to check though
     * @param userData
     */
    void handleUnknownSourceMsg(byte[] userData);

    /**
     * When socket was disconnected and deleted from Serverlist.
     */
    void onSocketDisconnected(MRUDPSocketImpl socket);

}

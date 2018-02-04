package ru.maklas.mrudp;

public interface MDisconnectionListener {

    /**
     * This method is going to be triggered on Thread that calls {@link MRUDPSocket#close()} or {@link MRUDPSocket#receive(SocketProcessor)}.
     * Notifies that socket was disconnected
     * @param socket socket that was disconnected
     * @param msg disconnection message. Look {@link MRUDPSocket} public static fields for defaults.
     */
    void onDisconnect(MRUDPSocket socket, String msg);

}

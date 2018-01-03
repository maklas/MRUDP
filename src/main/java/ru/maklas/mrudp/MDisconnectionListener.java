package ru.maklas.mrudp;

public interface MDisconnectionListener {

    /**
     * This method is going to be triggered on Thread that calls {@link MRUDPSocket#close()} or {@link MRUDPSocket#receive(SocketProcessor)}.
     * Notifies that socket was disconnected
     * @param socket socket that was disconnected
     */
    void onDisconnect(MRUDPSocket socket);

}

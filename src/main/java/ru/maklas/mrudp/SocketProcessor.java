package ru.maklas.mrudp;

/**
 * Class that implement this method can process events coming from MRUDPSockets
 */
public interface SocketProcessor {

    /**
     * Receives next packet from connected socket. Acts like iterator (socket.forEachNextPacket( (data) -> {}))
     * can be interrupted with help of SocketIterator.
     * @param data data that's received
     * @param socket socket from which data has come.
     * @param iterator iterator that helps with data manipulation
     */
    void process(byte[] data, MRUDPSocket socket, SocketIterator iterator);

}

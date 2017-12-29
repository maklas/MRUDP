package ru.maklas.mrudp;

public interface SocketIterator {

    /**
     * Call to skip next packet from being received until next {@link MRUDPSocket#receive(SocketProcessor)} method is called
     */
    void stop();

    /**
     * @return Whether this receiving command is interrupted/stopped
     */
    boolean isProcessing();

}

package ru.maklas.mrudp;

public interface SocketProcessor {

    void process(byte[] data, MRUDPSocket socket, SocketIterator iterator);

}

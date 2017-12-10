package ru.maklas.mrudp;

public interface SocketProcessor {

    void process(byte[] data, MRUDPSocket2 socket, SocketIterator iterator);

}

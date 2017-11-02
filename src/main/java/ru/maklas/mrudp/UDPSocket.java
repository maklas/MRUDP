package ru.maklas.mrudp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.SocketException;

/**
 * Created by amaklakov on 02.11.2017.
 */
public interface UDPSocket {

    /**
     * Local port to which this socket is bind
     */
    int getLocalPort();

    /**
     * Sends containment of {@link DatagramPacket}. Throws {@link java.io.IOException} in bad cases
     */
    void send(DatagramPacket packet) throws Exception ;

    /**
     * Blocks until the next datagram is received.
     * Throws {@link SocketException} if socket is getting close
     */
    void receive(DatagramPacket packet) throws IOException;

    /**
     * Any thread currently blocked in {@link #receive} upon this socket
     * will throw a {@link SocketException}.
     */
    void close();

}

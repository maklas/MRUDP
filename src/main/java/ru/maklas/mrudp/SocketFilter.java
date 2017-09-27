package ru.maklas.mrudp;

import java.net.InetAddress;

/**
 * Created by maklas on 11.09.2017.
 * Filters request for socket by address, port and request data
 */
public interface SocketFilter {

    /**
     *  <p>Filters request</p>
     *  <li><b>True</b> to accept</li>
     *  <li><b>False</b> to decline</li>
     */
    boolean filter(InetAddress address, int port, Request request);

    /**
     * Error code that has to be returned if packet was filtered
     */
    int errorCodeToReturn();

}

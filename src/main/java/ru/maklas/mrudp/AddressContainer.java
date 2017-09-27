package ru.maklas.mrudp;

import java.net.InetAddress;

/**
 * Created by maklas on 11.09.2017.
 * Stores address and port of a destination
 */
public interface AddressContainer {

    InetAddress getAddress();

    int getPort();

}

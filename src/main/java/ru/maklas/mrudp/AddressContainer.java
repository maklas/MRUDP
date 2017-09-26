package ru.maklas.mrudp;

import java.net.InetAddress;

/**
 * Created by maklas on 11.09.2017.
 * Хранит в себе адрес назначения
 */
public interface AddressContainer {

    InetAddress getAddress();

    int getPort();

}

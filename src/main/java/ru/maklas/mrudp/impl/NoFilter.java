package ru.maklas.mrudp.impl;


import ru.maklas.mrudp.Request;
import ru.maklas.mrudp.SocketFilter;
import ru.maklas.mrudp.SocketUtils;

import java.net.InetAddress;

/**
 * Created by maklas on 11.09.2017.
 * <p>Пустой фильтр. Пропускает все запросы</p>
 */
public class NoFilter implements SocketFilter {

    @Override
    public boolean filter(InetAddress address, int port, Request request) {
        return true;
    }

    @Override
    public int errorCodeToReturn() {
        return SocketUtils.CONNECTION_REFUSED_ERROR;
    }
}

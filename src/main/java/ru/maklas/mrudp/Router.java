package ru.maklas.mrudp;

import java.util.List;

public interface Router {

    UDPSocket getNewConnection();

    UDPSocket getNewConnection(int port);

    List<? extends UDPSocket> getAllSockets();
}

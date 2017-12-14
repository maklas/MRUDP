package ru.maklas.locator;

import java.net.InetAddress;

public interface BroadcastProcessor {


    byte[] process(InetAddress address, int port, byte[] request);

}

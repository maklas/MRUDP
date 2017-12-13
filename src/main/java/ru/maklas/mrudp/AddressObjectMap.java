package ru.maklas.mrudp;

import java.lang.reflect.Array;
import java.net.InetAddress;
import java.util.Collection;
import java.util.HashMap;

public class AddressObjectMap<T> {

    private final HashMap<Long, T> map = new HashMap<Long, T>();

    public void put(InetAddress address, int port, T o){
        long hash = hash(address, port);
        map.put(hash, o);
    }

    public T get(InetAddress address, int port){
        return map.get(hash(address, port));
    }

    private static final long hash(InetAddress address, int port){
        byte[] addressBytes = address.getAddress();
        long ret =    addressBytes[0] << 24         |
                (addressBytes[1] & 0xFF) << 16 |
                (addressBytes[2] & 0xFF) << 8  |
                (addressBytes[3] & 0xFF);
        ret += ((long) port) << 32;
        return ret;
    }

    public T remove(InetAddress address, int port) {
        return map.remove(hash(address, port));
    }

    public T[] values(T[] type) {
        return map.values().toArray(type);
    }
}

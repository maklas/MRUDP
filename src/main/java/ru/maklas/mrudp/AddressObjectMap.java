package ru.maklas.mrudp;

import java.net.InetAddress;

/**
 * Map that can store Objects by InetAddress+port value. Does not produce allocations.
 * <b>Warning: </b> Using hash of InetAddress, meaning no IPv6 must be used!
 * @param <T>
 */
public class AddressObjectMap<T> {

    private final LongMap<T> map = new LongMap<T>();

    public void put(InetAddress address, int port, T o){
        long hash = hash(address, port);
        map.put(hash, o);
    }

    public T get(InetAddress address, int port){
        return map.get(hash(address, port));
    }

    private static long hash(InetAddress address, int port){
        long ret = address.hashCode();
        ret += ((long) port) << 32;
        return ret;
    }

    public T remove(InetAddress address, int port) {
        return map.remove(hash(address, port));
    }

    public Iterable<T> values() {
        return map.values();
    }

    public void clear() {
        map.clear();
    }


    public static class Synchronized<T> extends AddressObjectMap<T> {
        @Override
        public void put(InetAddress address, int port, T o) {
            synchronized (this){
                super.put(address, port, o);
            }
        }

        @Override
        public T get(InetAddress address, int port) {
            synchronized (this){
                return super.get(address, port);
            }
        }

        @Override
        public T remove(InetAddress address, int port) {
            synchronized (this){
                return super.remove(address, port);
            }
        }

        @Override
        public Iterable<T> values() {
            synchronized (this){
                Iterable<T> values = super.values();
                Array<T> copy = new Array<T>();
                for (T value : values) {
                    copy.add(value);
                }
                return copy;
            }
        }

        @Override
        public void clear() {
            synchronized (this) {
                super.clear();
            }
        }
    }


}

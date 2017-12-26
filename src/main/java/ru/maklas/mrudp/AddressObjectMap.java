package ru.maklas.mrudp;

import ru.maklas.utils.LongMap;

import java.net.InetAddress;
import java.util.HashMap;

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

    public T[] values(T[] type) {
        return map.copyValues();
    }
    
    public void putNew(InetAddress address, int port, T val){

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
        public T[] values(T[] type) {
            synchronized (this){
                return super.values(type);
            }
        }

        @Override
        public void putNew(InetAddress address, int port, T val) {
            synchronized (this){
                super.putNew(address, port, val);
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

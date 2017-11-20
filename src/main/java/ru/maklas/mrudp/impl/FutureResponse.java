package ru.maklas.mrudp.impl;

/**
 * Created by amaklakov on 03.11.2017.
 */
public class FutureResponse {


    private ResponsePackage data = null;
    private boolean released = false;
    private ResponseHandlerAdapter handler;

    synchronized void put(ResponsePackage data){
        if (released){
            return;
        }
        this.data = data;
        this.released = true;
        this.notify();
    }

    public synchronized ResponsePackage get(){
        try {
            while (!released){
                wait();
            }
        } catch (InterruptedException ignore) {}

        return data;
    }

    public synchronized boolean isReady(){
        return released;
    }

    void setHandler(ResponseHandlerAdapter handler) {
        this.handler = handler;
    }

    public void stop(){
        handler.setKeepResending(false);
    }
}

package ru.maklas.mrudp.impl;

import ru.maklas.mrudp.Request;
import ru.maklas.mrudp.Response;
import ru.maklas.mrudp.ResponseHandler;

public class ResponseHandlerAdapter extends ResponseHandler{

    private volatile boolean keepResend = true;

    public ResponseHandlerAdapter() {
        super();
    }

    public ResponseHandlerAdapter(int timesToResend) {
        super(timesToResend);
    }

    @Override
    public void handle(Request request, Response response) {

    }

    @Override
    public void handleError(Request request, Response response, int errorCode) {

    }

    @Override
    public void discard(boolean internal, Request request) {

    }

    @Override
    public boolean keepResending() {
        return keepResend;
    }

    public void setKeepResending(boolean resend){
        keepResend = resend;
    }
}

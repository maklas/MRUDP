package ru.maklas.mrudp.impl;


import ru.maklas.mrudp.Request;
import ru.maklas.mrudp.Response;
import ru.maklas.mrudp.ResponseHandler;

/**
 * Created by maklas on 12.09.2017.
 * <p>Adapter with empty values and methods</p>
 */

public class ResponseAdapter implements ResponseHandler {


    @Override
    public void handle(Request request, Response response) {

    }

    @Override
    public void discard(Request request) {

    }

    @Override
    public void handleError(Request request, Response response, int errorCode) {

    }

    @Override
    public int getTimesToResend() {
        return 0;
    }
}

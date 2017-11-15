package ru.maklas.mrudp.impl;

import ru.maklas.mrudp.Request;
import ru.maklas.mrudp.RequestProcessor;
import ru.maklas.mrudp.ResponseWriter;
import ru.maklas.mrudp.SocketUtils;

import java.util.concurrent.LinkedBlockingQueue;

public class DelayedRequestProcessor implements RequestProcessor {

    private final LinkedBlockingQueue<DelayedRequest> queue;
    private final int responseTimeOut;

    public DelayedRequestProcessor(int responseTimeOut) {
        this.queue = new LinkedBlockingQueue<DelayedRequest>();
        this.responseTimeOut = responseTimeOut;
    }

    @Override
    public void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception {
        try {
            DelayedRequest delayedRequest = new DelayedRequest(request, response, responseRequired);
            queue.offer(delayedRequest);
            delayedRequest.assertResponse(responseTimeOut);
        } catch (InterruptedException e) {
            response.setResponseCode(SocketUtils.INTERNAL_SERVER_ERROR);
        }
    }


    public DelayedRequest poll(){
        return queue.poll();
    }

    public DelayedRequest take() throws InterruptedException {
        return queue.take();
    }

    public void clear(){
        DelayedRequest poll = queue.poll();
        while (poll != null){
            poll.responseEmpty();
            poll = queue.poll();
        }
    }


}

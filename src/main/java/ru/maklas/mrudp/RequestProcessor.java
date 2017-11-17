package ru.maklas.mrudp;

/**
 * Created by maklas on 11.09.2017.
 *
 * Processor for received requests. It's purpose is to read Requests and produce a responses for client some time later.
 * @see Request
 */
public interface RequestProcessor {

    /**
     * Processes Request and Produces a Response
     * @param request data from client
     * @param response data for client
     */
    void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception;

}

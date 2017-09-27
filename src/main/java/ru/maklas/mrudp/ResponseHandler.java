package ru.maklas.mrudp;

/**
 * Created by maklas on 11.09.2017.
 * Response processor.
 */
public interface ResponseHandler {

    /**
     * Handles response for previously sent request
     * @param request previously sent request
     * @param response response from server
     */
    void handle(Request request, Response response);

    /**
     * Same as handle(), but makes sure that we got an error from server
     */
    void handleError(Request request, Response response, int errorCode);

    /**
     * <p>Triggers if the request wasn't delivered to the server.</p>
     */
    void discard(Request request);

    /**
     * Sets how many times request should be resent before discarded
     */
    int getTimesToResend();

}

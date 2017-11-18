package ru.maklas.mrudp;

/**
 * Created by maklas on 11.09.2017.
 * Response processor.
 */
public abstract class ResponseHandler {

    private int timesToResend;

    public ResponseHandler() {

    }

    public ResponseHandler(int timesToResend) {
        this.timesToResend = timesToResend;
    }

    /**
     * Handles response for previously sent request
     * @param request previously sent request
     * @param response response from server
     */
    public abstract void handle(Request request, Response response);

    /**
     * Same as handle(), but makes sure that we got an error from server
     */
    public abstract void handleError(Request request, Response response, int errorCode);

    /**
     * <p>Triggers if the request wasn't delivered to the server.</p>
     */
    public abstract void discard(boolean internal, Request request);

    /**
     * Sets how many times request should be resent before discarded
     */
    public int getTimesToResend(){
        return timesToResend;
    }

    /**
     * Whether to keep requested packet or delete it without triggering discard or any method
     */
    public boolean keepResending() {
        return true;
    }

}

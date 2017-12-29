package ru.maklas.mrudp;

/**
 * Package which is used to respond on ConnectionRequest.
 * @param <T> will be send with response
 */
public class ConnectionResponsePackage<T> {

    private final boolean accept;
    private final T responseData;

    /**
     * Respond with success flag
     * @param data Your response data
     */
    public static <T> ConnectionResponsePackage<T> accept(T data){
        return new ConnectionResponsePackage<T>(true, data);
    }

    /**
     * Respond with refuse flag
     * @param data Your response data
     */
    public static <T> ConnectionResponsePackage<T> refuse(T data){
        return new ConnectionResponsePackage<T>(false, data);
    }

    private ConnectionResponsePackage(boolean accept, T responseData) {
        this.accept = accept;
        this.responseData = responseData;
    }

    public boolean accepted() {
        return accept;
    }

    public T getResponseData() {
        return responseData;
    }
}

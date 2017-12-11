package ru.maklas.mrudp;

public class ConnectionResponsePackage<T> {

    private final boolean accept;
    private final T responseData;

    public static <T> ConnectionResponsePackage<T> accept(T data){
        return new ConnectionResponsePackage<T>(true, data);
    }

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

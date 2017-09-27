package ru.maklas.mrudp;

/**
 * Created by maklas on 12.09.2017.
 * Request from server with the ability to write in it
 */
public interface RequestWriter extends Request {

    void incTimesRequested();

    void setTimesRequested(int times);

}

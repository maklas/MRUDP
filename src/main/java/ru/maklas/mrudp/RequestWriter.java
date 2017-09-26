package ru.maklas.mrudp;

/**
 * Created by maklas on 12.09.2017.
 * Ответ от сервера с возможностью заносить данные
 */
public interface RequestWriter extends Request {

    void incTimesRequested();

    void setTimesRequested(int times);

}

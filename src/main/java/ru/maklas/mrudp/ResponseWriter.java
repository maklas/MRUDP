package ru.maklas.mrudp;

/**
 * Created by maklas on 12.09.2017.
 * Пакет данных для ответа клиенту
 */

public interface ResponseWriter extends AddressContainer, Response {

    /**
     * Установка данных для отправки в ответе
     */
    void setData(byte[] data);

    /**
     * Тип ответа (OK/ERROR)
     */
    void setResponseCode(int responseCode);

    /**
     * Установка данных для отправки в ответе
     */
    void setData(String data);

}

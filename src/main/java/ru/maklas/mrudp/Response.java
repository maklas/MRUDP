package ru.maklas.mrudp;

/**
 * Created by maklas on 11.09.2017.
 * Ответ от сервера
 */
public interface Response extends AddressContainer {

    /**
     * @return Данные содержащиеся в ответе
     */
    byte[] getData();

    /**
     * @return Данные содержащиеся в ответе
     */
    String getDataAsString();

    /**
     * @return Код ответа (OK/ERROR)
     */
    int getResponseCode();

    /**
     * @return Номер пакета
     */
    int getSequenceNumber();

    String toString();

}

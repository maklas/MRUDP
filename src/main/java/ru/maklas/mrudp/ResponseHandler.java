package ru.maklas.mrudp;

/**
 * Created by maklas on 11.09.2017.
 * Обработчик ответов на отправленный ранее запрос
 */
public interface ResponseHandler {

    /**
     * Обработка ответа от сервера
     * Исполняется многопоточно
     * @param request ранее отправленный реквест
     * @param response ответ полученный от сервера
     */
    void handle(Request request, Response response);

    /**
     * Ничем не отличается от handle(), заисключением того, что в ответе от сервера пришла ошибка
     * Также исполняется многопоточно
     */
    void handleError(Request request, Response response, int errorCode);

    /**
     * <p>Действие которое нужно применить если сообщение не было доставлено на сервер.</p>
     * Это может быть отчистка данных или попытка переотправить данные
     */
    void discard(Request request);

    /**
     * Определяет сколько раз нужно переотправить запрос если он не удался
     */
    int getTimesToResend();

}

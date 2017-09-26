package ru.maklas.mrudp;

/**
 * Created by maklas on 11.09.2017.
 * Обработчик получаемых запросов, задача которого читать @see request, записывать данные в response,
 * в случае если это необходимо
 */
public interface RequestProcessor {

    /**
     * Отвечает на запрос от клиента. Этот метод может вызываться несколькими тредами сразу!
     * В request создаржатся данные от клиента.
     * В response записывается ответ для клиента.
     * @param request data from client
     * @param response data for client
     */
    void process(Request request, ResponseWriter response, boolean responseRequired) throws Exception;

}

package ru.maklas.locator;

public interface Notifier<T> {

    /**
     * Called when someone responded for discovery message
     */
    void notify(T t);

    /**
     * Called when Discovery is finished.
     * @param interrupted whether or not current discovery was interrupted by calling {@link Locator#interruptDiscovering()}
     */
    void finish(boolean interrupted);

}

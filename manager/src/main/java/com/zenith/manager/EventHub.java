package com.zenith.manager;

import com.google.gson.JsonObject;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fan-out of live events (status changes, log lines, login progress) to connected web clients.
 * Listeners must not block; they're expected to filter by permission and enqueue.
 */
public final class EventHub {
    public record Event(String type, String instanceId, JsonObject payload) {}

    private final Set<Consumer<Event>> listeners = ConcurrentHashMap.newKeySet();

    public void subscribe(Consumer<Event> listener) {
        listeners.add(listener);
    }

    public void unsubscribe(Consumer<Event> listener) {
        listeners.remove(listener);
    }

    public void publish(String type, String instanceId, JsonObject payload) {
        var event = new Event(type, instanceId, payload);
        for (var l : listeners) {
            try {
                l.accept(event);
            } catch (Exception ignored) {
                // one broken client must not affect others
            }
        }
    }
}

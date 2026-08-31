package io.cloudagent.gateway.session;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Fans out serialized Copilot agent events to whichever WebSocket clients are currently
 * subscribed to a given session. Kept independent of the WebSocket transport so it can be unit
 * tested without a servlet container.
 */
@Component
public class AgentEventHub {

    private final ConcurrentHashMap<String, Set<Consumer<String>>> subscribers = new ConcurrentHashMap<>();

    public void subscribe(String sessionId, Consumer<String> subscriber) {
        subscribers.computeIfAbsent(sessionId, id -> ConcurrentHashMap.newKeySet()).add(subscriber);
    }

    public void unsubscribe(String sessionId, Consumer<String> subscriber) {
        Set<Consumer<String>> set = subscribers.get(sessionId);
        if (set != null) {
            set.remove(subscriber);
        }
    }

    public void publish(String sessionId, String jsonEvent) {
        Set<Consumer<String>> set = subscribers.get(sessionId);
        if (set == null) {
            return;
        }
        for (Consumer<String> subscriber : set) {
            subscriber.accept(jsonEvent);
        }
    }
}

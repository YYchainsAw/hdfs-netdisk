package xyz.yychainsaw.backend.security;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** A bounded per-client limit for a single application instance. */
@Component
class LoginRateLimiter {
    private record Window(long expiresAt, int attempts) {}
    private final Map<String, Window> clients = new HashMap<>();
    synchronized boolean allow(String client) {
        long now = Instant.now().getEpochSecond();
        clients.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
        Window current = clients.get(client);
        if (current == null) {
            if (clients.size() >= 10000) return false;
            clients.put(client, new Window(now + 300, 1)); return true;
        }
        if (current.attempts() >= 10) return false;
        clients.put(client, new Window(current.expiresAt(), current.attempts() + 1));
        return true;
    }
    synchronized void success(String client) { clients.remove(client); }
}

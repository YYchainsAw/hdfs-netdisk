package xyz.yychainsaw.backend.security;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bounded, process-local limits; suitable only for the current single instance. */
@Component
final class AccountRateLimiter {
    private record Window(long until, int count) {}
    private final Map<String, Window> attempts = new HashMap<>();
    synchronized boolean allow(String key, int limit) {
        long now = Instant.now().getEpochSecond();
        attempts.entrySet().removeIf(e -> e.getValue().until() <= now);
        Window current = attempts.get(key);
        if (current == null) {
            if (attempts.size() >= 10000) return false;
            attempts.put(key, new Window(now + 600, 1));
            return true;
        }
        if (current.count() >= limit) return false;
        attempts.put(key, new Window(current.until(), current.count() + 1));
        return true;
    }
}

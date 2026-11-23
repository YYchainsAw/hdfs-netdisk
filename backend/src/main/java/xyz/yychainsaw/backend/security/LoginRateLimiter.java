package xyz.yychainsaw.backend.security;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bounded, single-instance login budgets for client IPs and client/account pairs. */
@Component
class LoginRateLimiter {
    private static final int MAX_KEYS = 10_000;
    private static final int CLIENT_LIMIT = 50;
    private static final int ACCOUNT_LIMIT = 10;
    private record Window(long expiresAt, int attempts) {}
    private record AccountKey(String client, String account) {}
    private final Map<String, Window> clients = new HashMap<>();
    private final Map<AccountKey, Window> accounts = new HashMap<>();

    private static <T> boolean consume(Map<T, Window> windows, T key, int limit, long now) {
        windows.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
        Window current = windows.get(key);
        if (current == null) {
            if (windows.size() >= MAX_KEYS) return false;
            windows.put(key, new Window(now + 300, 1));
            return true;
        }
        if (current.attempts() >= limit) return false;
        windows.put(key, new Window(current.expiresAt(), current.attempts() + 1));
        return true;
    }
    synchronized boolean allowClient(String client) {
        return consume(clients, client, CLIENT_LIMIT, Instant.now().getEpochSecond());
    }
    synchronized boolean allowAccount(String client, String account) {
        return consume(accounts, new AccountKey(client, account), ACCOUNT_LIMIT, Instant.now().getEpochSecond());
    }
    synchronized void success(String client, String account) {
        accounts.remove(new AccountKey(client, account));
    }
}

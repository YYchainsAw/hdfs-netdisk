package xyz.yychainsaw.backend.security;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {
    @Test void ipBudgetDoesNotResetOnSuccessAndLimitsEntireClient() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i=0; i<50; i++) {
            assertTrue(limiter.allowClient("client-a"));
            assertTrue(limiter.allowAccount("client-a", "id:self"));
            limiter.success("client-a", "id:self");
        }
        assertFalse(limiter.allowClient("client-a"));
        assertTrue(limiter.allowClient("client-b"));
    }
    @Test void successfulLoginResetsOnlyThatClientAndAccount() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i=0; i<10; i++) assertTrue(limiter.allowAccount("client-a", "id:victim"));
        limiter.success("client-a", "id:self");
        assertFalse(limiter.allowAccount("client-a", "id:victim"));
        assertTrue(limiter.allowAccount("client-a", "id:self"));
        assertTrue(limiter.allowAccount("client-b", "id:victim"));
        limiter.success("client-a", "id:victim");
        assertTrue(limiter.allowAccount("client-a", "id:victim"));
    }
}

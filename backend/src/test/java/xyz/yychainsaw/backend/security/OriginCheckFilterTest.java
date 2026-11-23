package xyz.yychainsaw.backend.security;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OriginCheckFilterTest {
    private MockHttpServletRequest request(String origin) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/folders");
        req.setScheme("https"); req.setServerName("netdisk.example"); req.setServerPort(443);
        if (origin != null) req.addHeader("Origin", origin);
        return req;
    }
    @Test void acceptsMatchingAndAbsentOrigin() throws Exception {
        for (String origin : List.of("https://netdisk.example", "https://netdisk.example:443")) {
            var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
            new OriginCheckFilter().doFilter(request(origin), response, chain);
            assertNotNull(chain.getRequest());
        }
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        new OriginCheckFilter().doFilter(request(null), response, chain);
        assertNotNull(chain.getRequest()); // CSRF protection is a separate filter.
    }
    @Test void rejectsForgedOrMalformedOrigins() throws Exception {
        for (String origin : List.of("null", "http://netdisk.example", "https://evil.example", "https://netdisk.example.evil.example", "https://user@netdisk.example", "https://netdisk.example/path", "https://netdisk.example:444", "https://netdisk.example?x=1")) {
            var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
            new OriginCheckFilter().doFilter(request(origin), response, chain);
            assertEquals(403, response.getStatus(), origin);
            assertNull(chain.getRequest(), origin);
        }
    }
}

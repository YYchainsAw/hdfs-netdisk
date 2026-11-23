package xyz.yychainsaw.backend.security;

import static org.junit.jupiter.api.Assertions.*;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:security_rate_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class LoginRateBypassTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    @BeforeEach void setup() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS nd_user (id BIGINT PRIMARY KEY, username VARCHAR_IGNORECASE(64) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL, status VARCHAR(16) NOT NULL)");
        jdbc.update("DELETE FROM nd_user");
        jdbc.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES(1,'self',?,'ACTIVE'),(2,'victim',?,'ACTIVE')",passwords.encode("correct-test-password"),passwords.encode("victim-test-password"));
    }
    String csrf(String body) {
        var m=Pattern.compile("\\\"csrfToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body);
        assertTrue(m.find()); return m.group(1);
    }
    HttpResponse<String> getToken() throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> login(String user,String password,String token) throws Exception {
        String body="{\"username\":\""+user+"\",\"password\":\""+password+"\"}";
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login")).header("Content-Type","application/json").header("X-CSRF-TOKEN",token).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(req,HttpResponse.BodyHandlers.ofString());
    }
    @Test void successfulOwnLoginCannotResetIpBudgetForOtherAccounts() throws Exception {
        String token=csrf(getToken().body());
        for(int i=0;i<10;i++) {
            assertEquals(401,login(i%2==0 ? "VICTIM" : "victim","wrong-test-password",token).statusCode());
            var success=login("self","correct-test-password",token);
            assertEquals(200,success.statusCode());
            token=csrf(success.body());
        }
        assertEquals(429,login("victim","wrong-test-password",token).statusCode(),"successful own-account logins must not reset the shared IP request budget");
    }
}

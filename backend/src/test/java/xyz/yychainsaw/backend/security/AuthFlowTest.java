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
    "spring.datasource.url=jdbc:h2:mem:auth_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class AuthFlowTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

    @BeforeEach void setup() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS nd_user (id BIGINT PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL, status VARCHAR(16) NOT NULL)");
        jdbc.update("DELETE FROM nd_user");
        jdbc.update("INSERT INTO nd_user(id, username, password_hash, status) VALUES(?,?,?,?)",1L,"alice",passwords.encode("example-password"),"ACTIVE");
    }

    private HttpResponse<String> request(String method, String path, String body, String csrf, String origin) throws Exception {
        HttpRequest.Builder req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/"+path));
        if (csrf != null) req.header("X-CSRF-TOKEN",csrf);
        if (origin != null) req.header("Origin",origin);
        if (method.equals("GET")) req.GET();
        else req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        return client.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }

    private String csrf(String body) {
        var m=Pattern.compile("\\\"csrfToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body);
        assertTrue(m.find(),body); return m.group(1);
    }

    @Test void csrfSessionLoginRotationAndLogout() throws Exception {
        assertEquals(401,request("GET","me",null,null,null).statusCode());
        var initial=request("GET","csrf",null,null,null);
        assertEquals(200,initial.statusCode());
        String first=csrf(initial.body());
        String oldSession=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        String login="{\"username\":\"alice\",\"password\":\"example-password\"}";
        assertEquals(403,request("POST","login",login,null,null).statusCode());
        assertEquals(403,request("POST","login",login,first,"https://attacker.example").statusCode());
        var signedIn=request("POST","login",login,first,null);
        assertEquals(200,signedIn.statusCode(),signedIn.body());
        assertTrue(signedIn.body().contains("alice"));
        String second=csrf(signedIn.body());
        String newSession=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        assertNotEquals(oldSession,newSession);
        assertEquals(200,request("GET","me",null,null,null).statusCode());
        assertEquals(403,request("POST","logout","",first,null).statusCode());
        assertEquals(204,request("POST","logout","",second,null).statusCode());
        assertEquals(401,request("GET","me",null,null,null).statusCode());
    }

    @Test void rejectsWrongOrUnknownCredentialsUniformly() throws Exception {
        String token=csrf(request("GET","csrf",null,null,null).body());
        var wrong=request("POST","login","{\"username\":\"alice\",\"password\":\"incorrect\"}",token,null);
        var unknown=request("POST","login","{\"username\":\"unknown\",\"password\":\"incorrect\"}",token,null);
        assertEquals(401,wrong.statusCode()); assertEquals(401,unknown.statusCode());
        assertEquals(wrong.body(),unknown.body());
    }

    @Test void disablingAccountInvalidatesExistingSession() throws Exception {
        String token=csrf(request("GET","csrf",null,null,null).body());
        String login="{\"username\":\"alice\",\"password\":\"example-password\"}";
        assertEquals(200,request("POST","login",login,token,null).statusCode());
        jdbc.update("UPDATE nd_user SET status='DISABLED' WHERE id=1");
        assertEquals(401,request("GET","me",null,null,null).statusCode());
    }
}

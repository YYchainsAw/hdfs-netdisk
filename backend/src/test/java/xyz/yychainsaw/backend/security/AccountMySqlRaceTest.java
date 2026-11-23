package xyz.yychainsaw.backend.security;

import static org.junit.jupiter.api.Assertions.*;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Run only against the temporary MySQL database created by run-account-mysql-test.sh. */
@EnabledIfEnvironmentVariable(named="ACCOUNT_TEST_DB_URL", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/account_qa.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=${ACCOUNT_TEST_DB_URL}",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
    "spring.datasource.username=root", "spring.datasource.password=${ACCOUNT_TEST_DB_PASSWORD}"
})
class AccountMySqlRaceTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    private HttpResponse<String> request(HttpClient c,String method,String path,String body,String csrf) throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/"+path));
        if(csrf!=null) r.header("X-CSRF-TOKEN",csrf);
        if(method.equals("GET")) r.GET(); else r.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return c.send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String csrf(HttpClient c) throws Exception {
        var m=Pattern.compile("\\\"csrfToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(request(c,"GET","csrf",null,null).body());
        assertTrue(m.find()); return m.group(1);
    }
    @Test void concurrentRegistrationNamesCollideCaseInsensitively() throws Exception {
        db.execute("CREATE TABLE nd_user (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, username VARCHAR(64) NOT NULL, password_hash VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, status VARCHAR(16) NOT NULL, UNIQUE KEY uk_username(username)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");
        var a=HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var b=HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String ca=csrf(a), cb=csrf(b);
        var first=CompletableFuture.supplyAsync(()->register(a,ca,"RaceUser"));
        var second=CompletableFuture.supplyAsync(()->register(b,cb,"raceuser"));
        int x=first.get(), y=second.get();
        assertTrue((x==201 && y==409)||(x==409 && y==201),"statuses: "+x+", "+y);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM nd_user WHERE username='raceuser'",Integer.class));
    }
    private int register(HttpClient c,String token,String name) {
        try { return request(c,"POST","register","{\"username\":\""+name+"\",\"password\":\"test-password-42\"}",token).statusCode(); }
        catch(Exception e){ throw new RuntimeException(e); }
    }
}

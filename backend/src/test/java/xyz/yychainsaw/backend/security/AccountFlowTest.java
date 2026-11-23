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
    "spring.datasource.url=jdbc:h2:mem:account_flow;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class AccountFlowTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @BeforeEach void setup() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS nd_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR_IGNORECASE(64) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL, status VARCHAR(16) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nd_node (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, node_type VARCHAR(8) NOT NULL, status VARCHAR(16) NOT NULL, size_bytes BIGINT)");
        jdbc.update("DELETE FROM nd_node"); jdbc.update("DELETE FROM nd_user");
        jdbc.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES (1,'alice',?,'ACTIVE')",passwords.encode("example-password"));
    }
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager()).build(); }
    private HttpResponse<String> request(HttpClient c,String method,String path,String body,String csrf) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path));
        if(csrf!=null) req.header("X-CSRF-TOKEN",csrf);
        if(method.equals("GET")) req.GET(); else req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body==null?"":body));
        return c.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String token(String s) {
        var m=Pattern.compile("\\\"csrfToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(s);
        assertTrue(m.find(),s); return m.group(1);
    }
    private String login(HttpClient c) throws Exception {
        String csrf=token(request(c,"GET","auth/csrf",null,null).body());
        var login=request(c,"POST","auth/login","{\"username\":\"alice\",\"password\":\"example-password\"}",csrf);
        assertEquals(200,login.statusCode(),login.body()); return token(login.body());
    }
    @Test void registrationIsCsrfProtectedValidatesAndPersistsHashedPassword() throws Exception {
        HttpClient c=client();
        String json="{\"username\":\"new_user\",\"password\":\"new-strong-password\"}";
        assertEquals(403,request(c,"POST","auth/register",json,null).statusCode());
        String csrf=token(request(c,"GET","auth/csrf",null,null).body());
        assertEquals(400,request(c,"POST","auth/register","{\"username\":\"x\",\"password\":\"a\"}",csrf).statusCode());
        var created=request(c,"POST","auth/register",json,csrf);
        assertEquals(201,created.statusCode(),created.body());
        assertEquals(409,request(c,"POST","auth/register",json,csrf).statusCode());
        assertEquals(409,request(c,"POST","auth/register","{\"username\":\"NEW_USER\",\"password\":\"new-strong-password\"}",csrf).statusCode());
        assertEquals(400,request(c,"POST","auth/register","{\"username\":\"new2\",\"password\":\"short\"}",csrf).statusCode());
        assertEquals(429,request(c,"POST","auth/register",json,csrf).statusCode());
        String stored=jdbc.queryForObject("SELECT password_hash FROM nd_user WHERE username='new_user'",String.class);
        assertNotEquals("new-strong-password",stored); assertTrue(passwords.matches("new-strong-password",stored));
        assertEquals(401,request(c,"GET","auth/me",null,null).statusCode());
    }
    @Test void changePasswordRequiresOldPasswordAndInvalidatesAllSessions() throws Exception {
        HttpClient a=client(), b=client(); String tokenA=login(a); login(b);
        String wrong="{\"oldPassword\":\"wrong-password\",\"newPassword\":\"new-strong-password\"}";
        var rejected=request(a,"POST","auth/password",wrong,tokenA);
        assertEquals(400,rejected.statusCode()); assertTrue(rejected.body().contains("PASSWORD_INCORRECT"));
        assertTrue(passwords.matches("example-password",jdbc.queryForObject("SELECT password_hash FROM nd_user WHERE id=1",String.class)));
        var good=request(a,"POST","auth/password","{\"oldPassword\":\"example-password\",\"newPassword\":\"new-strong-password\"}",tokenA);
        assertEquals(204,good.statusCode(),good.body());
        assertEquals(401,request(a,"GET","auth/me",null,null).statusCode());
        assertEquals(401,request(b,"GET","auth/me",null,null).statusCode());
        assertTrue(passwords.matches("new-strong-password",jdbc.queryForObject("SELECT password_hash FROM nd_user WHERE id=1",String.class)));
    }
    @Test void usageCountsOnlyMyReadyFiles() throws Exception {
        jdbc.update("INSERT INTO nd_node(id,user_id,node_type,status,size_bytes) VALUES (1,1,'FILE','READY',123),(2,1,'FILE','PENDING',999),(3,1,'FOLDER','READY',NULL),(4,2,'FILE','READY',10000)");
        HttpClient a=client(); login(a);
        var response=request(a,"GET","usage",null,null);
        assertEquals(200,response.statusCode(),response.body());
        assertTrue(response.body().contains("\"usedBytes\":\"123\""),response.body());
        assertTrue(response.body().contains("\"fileCount\":\"1\""),response.body());
        assertEquals(401,request(client(),"GET","usage",null,null).statusCode());
    }
}

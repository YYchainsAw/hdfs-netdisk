package xyz.yychainsaw.backend.security;

import static org.junit.jupiter.api.Assertions.*;
import java.net.CookieManager;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Opt-in isolated H2 and separate loopback nginx; never contacts production DB. */
@EnabledIfEnvironmentVariable(named="NETDISK_PROXY_QA", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:proxy_security_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "server.forward-headers-strategy=framework"
})
class ProxyForwardingIT {
    @LocalServerPort int backendPort;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    static final String NGINX="/www/server/nginx/sbin/nginx";
    static final Path PRODUCTION_INCLUDE=Path.of("/www/server/panel/vhost/nginx/extension/netdisk.yychainsaw.xyz/netdisk-app.conf");
    static String token(String body) {
        var m=Pattern.compile("\\\"csrfToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body);
        assertTrue(m.find()); return m.group(1);
    }
    @Test void forgedClientAddressesCannotEvadePerAccountLimit() throws Exception {
        jdbc.execute("CREATE TABLE nd_user (id BIGINT PRIMARY KEY, username VARCHAR_IGNORECASE(64) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL, status VARCHAR(16) NOT NULL)");
        jdbc.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES(1,'self',?,'ACTIVE'),(2,'victim',?,'ACTIVE')",encoder.encode("own-test-password"),encoder.encode("victim-test-password"));
        String config=Files.readString(PRODUCTION_INCLUDE);
        int start=config.indexOf("location ^~ /api/ {");
        assertTrue(start>=0,"production proxy block must be present");
        int end=config.indexOf('\n',config.indexOf('}',start));
        assertTrue(end>start);
        String block=config.substring(start,end).replace("127.0.0.1:18084","127.0.0.1:"+backendPort);
        assertTrue(block.contains("proxy_set_header X-Forwarded-For $remote_addr;"));
        assertTrue(block.contains("proxy_set_header Forwarded \"\";"));
        int proxyPort;
        try(ServerSocket socket=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) { proxyPort=socket.getLocalPort(); }
        Path temp=Files.createTempDirectory("netdisk-proxy-qa-");
        Files.setPosixFilePermissions(temp,java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createDirectory(temp.resolve("logs"));
        String luaPath=Files.lines(Path.of("/www/server/nginx/conf/nginx.conf")).map(String::trim).filter(x -> x.startsWith("lua_package_path ")).findFirst().orElseThrow();
        Path conf=temp.resolve("nginx.conf");
        Files.writeString(conf,"pid "+temp+"/nginx.pid;\nerror_log "+temp+"/error.log warn;\nevents { worker_connections 32; }\nhttp { "+luaPath+" access_log off; server { listen 127.0.0.1:"+proxyPort+"; "+block+"\n} }\n");
        Process nginx=new ProcessBuilder(NGINX,"-p",temp.toString(),"-c",conf.toString(),"-g","daemon off;").redirectErrorStream(true).redirectOutput(temp.resolve("launcher.log").toFile()).start();
        try {
            var client=HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(2)).build();
            URI base=URI.create("http://127.0.0.1:"+proxyPort);
            HttpResponse<String> first=null;
            for(int i=0;i<30;i++) {
                try { first=client.send(HttpRequest.newBuilder(base.resolve("/api/v1/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()); break; }
                catch(java.io.IOException ex) { if(!nginx.isAlive()) throw new AssertionError("nginx exited: "+Files.readString(temp.resolve("launcher.log")),ex); Thread.sleep(100); }
            }
            assertNotNull(first); assertEquals(200,first.statusCode()); String csrf=token(first.body());
            for(int i=0;i<10;i++) {
                var bad=login(client,base,"victim","incorrect-test-password",csrf,i);
                assertEquals(401,bad.statusCode());
                var own=login(client,base,"self","own-test-password",csrf,i+30);
                assertEquals(200,own.statusCode()); csrf=token(own.body());
            }
            assertEquals(429,login(client,base,"victim","incorrect-test-password",csrf,99).statusCode(),"spoofed forwarding headers must not reset client/account quota");
        } finally {
            nginx.destroy(); if(!nginx.waitFor(5,TimeUnit.SECONDS)) { nginx.destroyForcibly(); nginx.waitFor(5,TimeUnit.SECONDS); }
            try(var files=Files.walk(temp)) { for(Path p:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
    static HttpResponse<String> login(HttpClient client, URI base, String name, String password, String csrf, int n) throws Exception {
        String body="{\"username\":\""+name+"\",\"password\":\""+password+"\"}";
        String spoof="198.51.100."+(n%200+1);
        var request=HttpRequest.newBuilder(base.resolve("/api/v1/auth/login"))
            .header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf)
            .header("X-Forwarded-For",spoof).header("X-Forwarded-Host","attacker.invalid")
            .header("X-Forwarded-Proto","https").header("X-Forwarded-Port","443")
            .header("X-Forwarded-Prefix","/bad").header("Forwarded","for="+spoof+";proto=https")
            .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
}


package xyz.yychainsaw.backend.controller;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
 "spring.datasource.url=jdbc:h2:mem:share_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password="
})
class ShareFlowTest {
 @LocalServerPort int port;
 @Autowired JdbcTemplate db;
 @Autowired PasswordEncoder passwords;
 @BeforeEach void setup() {
  db.execute("CREATE TABLE IF NOT EXISTS nd_user(id BIGINT AUTO_INCREMENT PRIMARY KEY,username VARCHAR_IGNORECASE(64) UNIQUE,password_hash VARCHAR(255),status VARCHAR(16))");
  db.execute("CREATE TABLE IF NOT EXISTS nd_node(id BIGINT PRIMARY KEY,user_id BIGINT,parent_id BIGINT,name VARCHAR(191),node_type VARCHAR(8),status VARCHAR(16),storage_key VARCHAR(255),size_bytes BIGINT,version BIGINT)");
  db.execute("CREATE TABLE IF NOT EXISTS nd_share(id BIGINT AUTO_INCREMENT PRIMARY KEY,owner_id BIGINT,node_id BIGINT,node_version BIGINT,token_hash CHAR(64) UNIQUE,code_hash VARCHAR(100),expires_at TIMESTAMP,revoked BOOLEAN DEFAULT FALSE,max_visits INT,access_count BIGINT DEFAULT 0,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
  db.update("DELETE FROM nd_share");db.update("DELETE FROM nd_node");db.update("DELETE FROM nd_user");
  db.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES(1,'alice',?,'ACTIVE'),(2,'bob',?,'ACTIVE')",passwords.encode("example-password"),passwords.encode("example-password"));
  db.update("INSERT INTO nd_node(id,user_id,parent_id,name,node_type,status,storage_key,size_bytes,version) VALUES(11,1,0,'sample.txt','FILE','READY','/netdisk/users/1/test',3,1)");
 }
 private HttpClient client() {return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();}
 private HttpResponse<String> req(HttpClient c,String method,String path,String body,String csrf) throws Exception {
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path));if(csrf!=null)b.header("X-CSRF-TOKEN",csrf);
  if(method.equals("GET"))b.GET();else if(method.equals("DELETE"))b.DELETE();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
  return c.send(b.build(),HttpResponse.BodyHandlers.ofString());
 }
 private String field(String json,String name) {var m=Pattern.compile("\\\""+name+"\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);assertTrue(m.find(),json);return m.group(1);}
 private String login(HttpClient c,String name) throws Exception {
  String token=field(req(c,"GET","auth/csrf",null,null).body(),"csrfToken");
  var res=req(c,"POST","auth/login","{\"username\":\""+name+"\",\"password\":\"example-password\"}",token);
  assertEquals(200,res.statusCode(),res.body());return field(res.body(),"csrfToken");
 }
 @Test void shareRequiresCorrectCodeHonorsOwnershipAndRevocation() throws Exception {
  HttpClient owner=client(), other=client(), visitor=client();String ownerCsrf=login(owner,"alice"),otherCsrf=login(other,"bob");
  assertEquals(404,req(other,"POST","shares","{\"nodeId\":\"11\",\"expiresHours\":24}",otherCsrf).statusCode());
  var created=req(owner,"POST","shares","{\"nodeId\":\"11\",\"expiresHours\":24}",ownerCsrf);assertEquals(201,created.statusCode(),created.body());
  String id=field(created.body(),"id"), token=field(created.body(),"token"), code=field(created.body(),"code");
  assertFalse(db.queryForObject("SELECT token_hash FROM nd_share WHERE id=?",String.class,Long.valueOf(id)).equals(token));
  assertEquals(200,req(visitor,"GET","public/shares/"+token,null,null).statusCode());
  assertEquals(403,req(visitor,"GET","public/shares/"+token+"/content",null,null).statusCode());
  String visitorCsrf=field(req(visitor,"GET","auth/csrf",null,null).body(),"csrfToken");
  assertEquals(400,req(visitor,"POST","public/shares/"+token+"/verify","{\"code\":\"0000000\"}",visitorCsrf).statusCode());
  assertEquals(200,req(visitor,"POST","public/shares/"+token+"/verify","{\"code\":\""+code+"\"}",visitorCsrf).statusCode());
  assertEquals(404,req(other,"DELETE","shares/"+id,null,otherCsrf).statusCode());
  assertEquals(204,req(owner,"DELETE","shares/"+id,null,ownerCsrf).statusCode());
  assertEquals(404,req(visitor,"GET","public/shares/"+token,null,null).statusCode());
  assertEquals(404,req(visitor,"GET","public/shares/"+token+"/content",null,null).statusCode());
 }
 @Test void deletingOrRenamingMakesExistingLinkUnavailable() throws Exception {
  HttpClient owner=client(),visitor=client();String csrf=login(owner,"alice");
  var created=req(owner,"POST","shares","{\"nodeId\":\"11\",\"expiresHours\":24}",csrf);String token=field(created.body(),"token");
  db.update("UPDATE nd_node SET status='DELETING',version=version+1 WHERE id=11");
  assertEquals(404,req(visitor,"GET","public/shares/"+token,null,null).statusCode());
  db.update("UPDATE nd_node SET status='READY' WHERE id=11");
  assertEquals(404,req(visitor,"GET","public/shares/"+token,null,null).statusCode());
 }
 @Test void visitLimitCountsOneVerifiedSessionAndRejectsFurtherSessions() throws Exception {
  HttpClient owner=client(), first=client(), second=client(); String csrf=login(owner,"alice");
  var created=req(owner,"POST","shares","{\"nodeId\":\"11\",\"expiresHours\":24,\"maxVisits\":1}",csrf);
  assertEquals(201,created.statusCode(),created.body()); String token=field(created.body(),"token"), code=field(created.body(),"code");
  String a=field(req(first,"GET","auth/csrf",null,null).body(),"csrfToken");
  String b=field(req(second,"GET","auth/csrf",null,null).body(),"csrfToken");
  String body="{\"code\":\""+code+"\"}";
  assertEquals(200,req(first,"POST","public/shares/"+token+"/verify",body,a).statusCode());
  assertEquals(200,req(first,"POST","public/shares/"+token+"/verify",body,a).statusCode());
  assertEquals(410,req(second,"POST","public/shares/"+token+"/verify",body,b).statusCode());
  assertEquals(1L,db.queryForObject("SELECT access_count FROM nd_share WHERE token_hash IS NOT NULL",Long.class));
 }

}

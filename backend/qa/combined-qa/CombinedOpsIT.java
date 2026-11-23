package xyz.yychainsaw.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import xyz.yychainsaw.backend.storage.HdfsStorage;

@ActiveProfiles("server")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
 "spring.datasource.url=${NETDISK_QA_DB_URL}","spring.datasource.username=root",
 "spring.datasource.password=${NETDISK_QA_DB_PASSWORD}","spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
 "server.servlet.session.cookie.secure=false",
 "netdisk.hdfs.temp-root=/netdisk/temp/${NETDISK_QA_ROOT}",
 "netdisk.hdfs.users-root=/netdisk/users/${NETDISK_QA_ROOT}"
})
class CombinedOpsIT {
 @LocalServerPort int port;
 @Autowired JdbcTemplate db;
 @Autowired HdfsStorage storage;
 @Autowired MultipartGarbageCollector gc;
 private record Session(HttpClient client, String csrf) {}
 private static final Pattern TOKEN=Pattern.compile("\\\"csrfToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
 private String text(HttpResponse<byte[]> r) { return new String(r.body(),StandardCharsets.UTF_8); }
 private String token(HttpResponse<byte[]> r) { var m=TOKEN.matcher(text(r)); assertTrue(m.find(),text(r)); return m.group(1); }
 private HttpResponse<byte[]> send(Session s,String method,String path,byte[] body,String type,String hash,String version) throws Exception {
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path));
  if(!method.equals("GET")) b.header("X-CSRF-TOKEN",s.csrf());
  if(type!=null) b.header("Content-Type",type);
  if(hash!=null) b.header("X-Chunk-SHA256",hash);
  if(version!=null) b.header("If-Match","\"v"+version+"\"");
  b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
  return s.client().send(b.build(),HttpResponse.BodyHandlers.ofByteArray());
 }
 private HttpResponse<byte[]> json(Session s,String method,String path,String json,String version) throws Exception {
  return send(s,method,path,json==null?null:json.getBytes(StandardCharsets.UTF_8),"application/json",null,version);
 }
 private Session anonymous() throws Exception {
  var client=HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
  var a=new Session(client,""); return new Session(client,token(send(a,"GET","auth/csrf",null,null,null,null)));
 }
 private Session login(String username,String password) throws Exception {
  var a=anonymous(); var response=json(a,"POST","auth/login","{\"username\":\""+username+"\",\"password\":\""+password+"\"}",null);
  assertEquals(200,response.statusCode(),text(response)); return new Session(a.client(),token(response));
 }
 private void assertStatus(int status,HttpResponse<byte[]> r) { assertEquals(status,r.statusCode(),text(r)); }
 @Test void registrationFilesCredentialsAndHdfs() throws Exception {
  String a="qa"+UUID.randomUUID().toString().replace("-","").substring(0,12), b="qb"+UUID.randomUUID().toString().replace("-","").substring(0,12);
  String password="Test-Pass-2026!", changed="New-Test-Pass-2026!";
  Session anon=anonymous();
  assertStatus(201,json(anon,"POST","auth/register","{\"username\":\""+a+"\",\"password\":\""+password+"\"}",null));
  assertStatus(409,json(anon,"POST","auth/register","{\"username\":\""+a.toUpperCase()+"\",\"password\":\""+password+"\"}",null));
  assertStatus(201,json(anon,"POST","auth/register","{\"username\":\""+b+"\",\"password\":\""+password+"\"}",null));
  var userId=db.queryForObject("SELECT id FROM nd_user WHERE username=?",Long.class,a);
  var stored=db.queryForObject("SELECT password_hash FROM nd_user WHERE username=?",String.class,a);
  assertNotNull(stored); assertNotEquals(password,stored); assertTrue(stored.startsWith("$2"));
  Session alice=login(a,password), aliceSecond=login(a,password), bob=login(b,password);
  byte[] data="isolated HDFS combined test".getBytes(StandardCharsets.UTF_8);
  String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  String request=UUID.randomUUID().toString();
  var created=json(alice,"POST","uploads","{\"parentId\":\"0\",\"name\":\"source.txt\",\"sizeBytes\":"+data.length+",\"requestId\":\""+request+"\",\"chunkHashes\":[\""+sha+"\"]}",null);
  assertStatus(200,created);
  var upload=db.queryForObject("SELECT id FROM nd_multipart_upload WHERE request_id=?",String.class,request);
  assertStatus(204,send(alice,"PUT","uploads/"+upload+"/chunks/0",data,"application/octet-stream",sha,null));
  assertStatus(200,json(alice,"POST","uploads/"+upload+"/complete","{\"resolution\":\"FAIL\"}",null));
  Long id=db.queryForObject("SELECT node_id FROM nd_multipart_upload WHERE id=?",Long.class,upload);
  String key=db.queryForObject("SELECT storage_key FROM nd_node WHERE id=?",String.class,id);
  assertTrue(storage.exists(key));
  var usage=send(alice,"GET","usage",null,null,null,null); assertStatus(200,usage); assertTrue(text(usage).contains("\"usedBytes\":\""+data.length+"\""),text(usage));
  var other=send(bob,"GET","usage",null,null,null,null); assertStatus(200,other); assertTrue(text(other).contains("\"usedBytes\":\"0\""),text(other));
  assertStatus(404,send(bob,"GET","nodes/"+id+"/content",null,null,null,null));
  assertStatus(404,json(bob,"PATCH","nodes/"+id+"/name","{\"name\":\"hijack\"}","1"));
  assertStatus(200,json(alice,"PATCH","nodes/"+id+"/name","{\"name\":\"renamed.txt\"}","1"));
  assertStatus(412,json(alice,"DELETE","nodes/"+id,null,"1"));
  assertArrayEquals(data,send(alice,"GET","nodes/"+id+"/content",null,null,null,null).body());
  var folder=json(alice,"POST","folders","{\"parentId\":\"0\",\"name\":\"folder\"}",null); assertStatus(201,folder);
  var folderId=db.queryForObject("SELECT id FROM nd_node WHERE user_id=? AND name='folder'",Long.class,userId);
  assertStatus(409,json(alice,"PATCH","nodes/"+id+"/name","{\"name\":\"folder\"}","2"));
  assertStatus(201,json(alice,"POST","folders","{\"parentId\":\""+folderId+"\",\"name\":\"child\"}",null));
  assertStatus(202,json(alice,"DELETE","nodes/"+folderId,null,"1"));
  var gate=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
  try {
   var one=pool.submit(()->{gate.await();return json(alice,"PATCH","nodes/"+id+"/name","{\"name\":\"first.txt\"}","2");});
   var two=pool.submit(()->{gate.await();return json(alice,"PATCH","nodes/"+id+"/name","{\"name\":\"second.txt\"}","2");});
   gate.countDown(); var r1=one.get(); var r2=two.get();
   assertTrue((r1.statusCode()==200 && r2.statusCode()==412)||(r1.statusCode()==412 && r2.statusCode()==200),text(r1)+text(r2));
  } finally { pool.shutdownNow(); }
  assertStatus(202,json(alice,"DELETE","nodes/"+id,null,"3"));
  assertStatus(404,send(alice,"GET","nodes/"+id+"/content",null,null,null,null));
  assertEquals("TRASHED",db.queryForObject("SELECT status FROM nd_node WHERE id=?",String.class,id));
  assertStatus(202,json(alice,"DELETE","trash/"+id,null,"4"));
  assertEquals("DELETING",db.queryForObject("SELECT status FROM nd_node WHERE id=?",String.class,id));
  gc.collect();
  assertFalse(storage.exists(key)); assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE id=?",Integer.class,id));
  var finalUsage=send(alice,"GET","usage",null,null,null,null); assertStatus(200,finalUsage); assertTrue(text(finalUsage).contains("\"usedBytes\":\"0\""),text(finalUsage));
  assertStatus(400,json(alice,"POST","auth/password","{\"oldPassword\":\"incorrect\",\"newPassword\":\""+changed+"\"}",null));
  assertStatus(204,json(alice,"POST","auth/password","{\"oldPassword\":\""+password+"\",\"newPassword\":\""+changed+"\"}",null));
  assertStatus(401,send(alice,"GET","auth/me",null,null,null,null));
  assertStatus(401,send(aliceSecond,"GET","auth/me",null,null,null,null));
  Session newSession=login(a,changed); assertStatus(200,send(newSession,"GET","auth/me",null,null,null,null));
 }
 private String uploadBody(String name, String request, String sha, int size) {
  return "{\"parentId\":\"0\",\"name\":\""+name+"\",\"sizeBytes\":"+size+",\"requestId\":\""+request+"\",\"chunkHashes\":[\""+sha+"\"]}";
 }
 private long finish(Session user,String name,byte[] data,String sha,String resolution) throws Exception {
  String request=UUID.randomUUID().toString();
  var created=json(user,"POST","uploads",uploadBody(name,request,sha,data.length),null); assertStatus(200,created);
  String id=db.queryForObject("SELECT id FROM nd_multipart_upload WHERE request_id=?",String.class,request);
  assertStatus(204,send(user,"PUT","uploads/"+id+"/chunks/0",data,"application/octet-stream",sha,null));
  assertStatus(200,json(user,"POST","uploads/"+id+"/complete","{\"resolution\":\""+resolution+"\"}",null));
  return db.queryForObject("SELECT node_id FROM nd_multipart_upload WHERE id=?",Long.class,id);
 }
 @Test void actualUploadPolicyAndCreatedAt() throws Exception {
  String name="policy"+UUID.randomUUID().toString().replace("-","").substring(0,10), password="Test-Pass-2026!";
  Session anon=anonymous(); assertStatus(401,send(anon,"GET","uploads/policy",null,null,null,null));
  assertStatus(201,json(anon,"POST","auth/register","{\"username\":\""+name+"\",\"password\":\""+password+"\"}",null));
  Session user=login(name,password);
  var policy=send(user,"GET","uploads/policy",null,null,null,null); assertStatus(200,policy);
  assertTrue(text(policy).contains("\"maxSizeBytes\":\"2147483648\""));
  assertTrue(text(policy).contains("\"pdf\""));
  byte[] data="valid type policy".getBytes(StandardCharsets.UTF_8);
  String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  assertStatus(400,json(user,"POST","uploads",uploadBody("test.pdf.exe",UUID.randomUUID().toString(),sha,data.length),null));
  assertStatus(400,json(user,"POST","uploads",uploadBody("filewithouttype",UUID.randomUUID().toString(),sha,data.length),null));
  long id=finish(user,"report.PDF",data,sha,"FAIL");
  var before=db.queryForObject("SELECT created_at FROM nd_node WHERE id=?",java.sql.Timestamp.class,id);
  assertNotNull(before);
  var list=send(user,"GET","nodes?parentId=0",null,null,null,null); assertStatus(200,list);
  assertTrue(text(list).contains("\"createdAt\":"),text(list));
  assertStatus(200,json(user,"PATCH","nodes/"+id+"/name","{\"name\":\"renamed.pdf\"}","1"));
  assertEquals(before,db.queryForObject("SELECT created_at FROM nd_node WHERE id=?",java.sql.Timestamp.class,id));
  finish(user,"report.PDF",data,sha,"FAIL");
  long copy=finish(user,"report.PDF",data,sha,"KEEP_BOTH");
  assertEquals("report (1).PDF",db.queryForObject("SELECT name FROM nd_node WHERE id=?",String.class,copy));
  assertStatus(201,json(user,"POST","folders","{\"parentId\":\"0\",\"name\":\"folder.txt\"}",null));
  long folderCopy=finish(user,"folder.txt",data,sha,"KEEP_BOTH");
  assertEquals("folder (1).txt",db.queryForObject("SELECT name FROM nd_node WHERE id=?",String.class,folderCopy));
  Long userId=db.queryForObject("SELECT id FROM nd_user WHERE username=?",Long.class,name);
  String oldId=UUID.randomUUID().toString(), oldReq=UUID.randomUUID().toString();
  db.update("INSERT INTO nd_multipart_upload(id,user_id,request_id,parent_id,name,size_bytes,chunk_size,status,expires_at) VALUES(?,?,?,0,'old.exe',0,8388608,'SKIPPED',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))",oldId,userId,oldReq);
  String oldBody="{\"parentId\":\"0\",\"name\":\"old.exe\",\"sizeBytes\":0,\"requestId\":\""+oldReq+"\",\"chunkHashes\":[]}";
  assertStatus(200,json(user,"POST","uploads",oldBody,null));

 }

}

package xyz.yychainsaw.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** Runs only through the isolated MySQL/HDFS harness, never against the production API. */
@ActiveProfiles("server")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
 "spring.datasource.url=${NETDISK_QA_DB_URL}","spring.datasource.username=root",
 "spring.datasource.password=${NETDISK_QA_DB_PASSWORD}","spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
 "server.servlet.session.cookie.secure=false",
 "netdisk.hdfs.temp-root=/netdisk/temp/${NETDISK_QA_ROOT}",
 "netdisk.hdfs.users-root=/netdisk/users/${NETDISK_QA_ROOT}"
})
class PerformanceBaselineIT {
 private static final int MIB=1024*1024, CHUNK=8*MIB;
 private static final Pattern TOKEN=Pattern.compile("\\\"csrfToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
 @LocalServerPort int port;
 @Autowired JdbcTemplate db;
 @Autowired PasswordEncoder encoder;
 private final AtomicInteger backoffs=new AtomicInteger();
 private record Session(HttpClient client,String csrf) {}
 private record Result(long bytes,double transfer,double publish,double download) {}
 private static String text(HttpResponse<byte[]> response) { return new String(response.body(),StandardCharsets.UTF_8); }
 private static String token(HttpResponse<byte[]> response) { var match=TOKEN.matcher(text(response)); assertTrue(match.find()); return match.group(1); }
 private HttpResponse<byte[]> send(Session s,String method,String path,byte[] body,String type,String digest) throws Exception {
  var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path));
  if(!method.equals("GET")) builder.header("X-CSRF-TOKEN",s.csrf());
  if(type!=null) builder.header("Content-Type",type);
  if(digest!=null) builder.header("X-Chunk-SHA256",digest);
  builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
  return s.client().send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
 }
 private HttpResponse<byte[]> json(Session s,String method,String path,String body) throws Exception {
  return send(s,method,path,body.getBytes(StandardCharsets.UTF_8),"application/json",null);
 }
 private Session login(String username,String password) throws Exception {
  var client=HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
  var anonymous=new Session(client,"");
  var initial=send(anonymous,"GET","auth/csrf",null,null,null);
  assertEquals(200,initial.statusCode());
  var result=json(new Session(client,token(initial)),"POST","auth/login","{\"username\":\""+username+"\",\"password\":\""+password+"\"}");
  assertEquals(200,result.statusCode());
  return new Session(client,token(result));
 }
 private static double seconds(long start) { return (System.nanoTime()-start)/1_000_000_000.0; }
 private Result uploadAndDownload(Session session,String name,int sizeMiB) throws Exception {
  long bytes=(long)sizeMiB*MIB;
  byte[] chunk=new byte[CHUNK];
  // Deterministic non-zero pattern; the full file is not buffered in heap.
  for(int i=0;i<chunk.length;i++) chunk[i]=(byte)((i*31+(i>>>8))&255);
  String chunkHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(chunk));
  int count=sizeMiB/8;
  String manifest=String.join(",",Collections.nCopies(count,"\""+chunkHash+"\""));
  String request=UUID.randomUUID().toString();
  var created=json(session,"POST","uploads","{\"parentId\":\"0\",\"name\":\""+name+"\",\"sizeBytes\":"+bytes+",\"requestId\":\""+request+"\",\"chunkHashes\":["+manifest+"]}");
  assertEquals(200,created.statusCode(),text(created));
  String id=db.queryForObject("SELECT id FROM nd_multipart_upload WHERE request_id=?",String.class,request);
  long transferStart=System.nanoTime();
  for(int index=0;index<count;index++) {
   var result=send(session,"PUT","uploads/"+id+"/chunks/"+index,chunk,"application/octet-stream",chunkHash);
   assertEquals(204,result.statusCode(),text(result));
  }
  double transfer=seconds(transferStart);
  long publishStart=System.nanoTime();
  HttpResponse<byte[]> completed;
  int retries=0;
  while(true) {
   completed=json(session,"POST","uploads/"+id+"/complete","{\"resolution\":\"FAIL\"}");
   if(completed.statusCode()!=429 || retries++>=40) break;
   // Only one server-side merge may run at a time; this is expected backpressure.
   backoffs.incrementAndGet(); Thread.sleep(250);
  }
  assertEquals(200,completed.statusCode(),text(completed));
  assertTrue(text(completed).contains("COMPLETED"));
  double publish=seconds(publishStart);
  long node=db.queryForObject("SELECT node_id FROM nd_multipart_upload WHERE id=?",Long.class,id);
  var expected=MessageDigest.getInstance("SHA-256");
  for(int i=0;i<count;i++) expected.update(chunk);
  long downloadStart=System.nanoTime();
  var response=session.client().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/nodes/"+node+"/content")).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
  assertEquals(200,response.statusCode());
  var actual=MessageDigest.getInstance("SHA-256");
  long read=0;
  try(InputStream in=response.body()) {
   byte[] buffer=new byte[65536]; int n;
   while((n=in.read(buffer))!=-1) { actual.update(buffer,0,n); read+=n; }
  }
  double download=seconds(downloadStart);
  assertEquals(bytes,read);
  assertArrayEquals(expected.digest(),actual.digest());
  return new Result(bytes,transfer,publish,download);
 }
 @Test void isolatedPerformanceBaseline() throws Exception {
  String root=System.getenv("NETDISK_QA_ROOT");
  assertNotNull(root);
  assertTrue(root.matches("test-[0-9a-f]{32}"),"An isolated HDFS root is mandatory");
  int sizeMiB=Integer.parseInt(System.getenv().getOrDefault("NETDISK_PERF_FILE_MIB","32"));
  assertTrue(sizeMiB>=8 && sizeMiB<=2048 && sizeMiB%8==0,"Size must be an 8 MiB multiple from 8 to 2048");
  String password="test-only-"+UUID.randomUUID();
  String first="perf"+UUID.randomUUID().toString().replace("-","").substring(0,12);
  String second="perf"+UUID.randomUUID().toString().replace("-","").substring(0,12);
  String encoded=encoder.encode(password);
  db.update("INSERT INTO nd_user(username,password_hash,status) VALUES(?,?,'ACTIVE'),(?,?,'ACTIVE')",first,encoded,second,encoded);
  Session one=login(first,password), two=login(first,password), three=login(second,password);
  Result single=uploadAndDownload(one,"single.bin",sizeMiB);
  System.out.printf("PERF scenario=single size_mib=%d transfer_s=%.3f publish_s=%.3f download_s=%.3f upload_mib_s=%.2f download_mib_s=%.2f sha256=ok%n",sizeMiB,single.transfer(),single.publish(),single.download(),sizeMiB/single.transfer(),sizeMiB/single.download());
  if("YES".equals(System.getenv("NETDISK_PERF_SKIP_PARALLEL"))) {
   System.out.println("PERF scenario=parallel skipped=explicit");
   return;
  }
  var gate=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(3);
  try {
   var f1=pool.submit(()->{gate.await(); return uploadAndDownload(one,"parallel-a.bin",16);});
   var f2=pool.submit(()->{gate.await(); return uploadAndDownload(two,"parallel-b.bin",16);});
   var f3=pool.submit(()->{gate.await(); return uploadAndDownload(three,"parallel-c.bin",16);});
   long start=System.nanoTime(); gate.countDown();
   Result r1=f1.get(),r2=f2.get(),r3=f3.get();
   double elapsed=seconds(start);
   System.out.printf("PERF scenario=parallel tasks=3 size_mib_each=16 elapsed_s=%.3f aggregate_upload_download_mib_s=%.2f merge_429_retries=%d checksums=3_ok%n",elapsed,(r1.bytes()+r2.bytes()+r3.bytes())*2.0/MIB/elapsed,backoffs.get());
  } finally { pool.shutdownNow(); }
 }
}

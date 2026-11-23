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
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.backend.repository.NdNodeMapper;
import java.io.ByteArrayInputStream;
import xyz.yychainsaw.backend.storage.HdfsStorage;

@ActiveProfiles("server")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=${NETDISK_QA_DB_URL}", "spring.datasource.username=root",
    "spring.datasource.password=${NETDISK_QA_DB_PASSWORD}", "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
    "server.servlet.session.cookie.secure=false",
    "netdisk.hdfs.temp-root=/netdisk/temp/${NETDISK_QA_ROOT}",
    "netdisk.hdfs.users-root=/netdisk/users/${NETDISK_QA_ROOT}"
})
class ReliabilityExtraIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder encoder;
    @Autowired MultipartGarbageCollector gc;
    @Autowired HdfsStorage storage;
    @Autowired TransactionTemplate tx;
    @Autowired NdNodeMapper nodes;
    private record Session(HttpClient client, String csrf) {}
    private record Upload(String id, String sha, byte[] data) {}
    private static final Pattern TOKEN = Pattern.compile("\\\"csrfToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private HttpResponse<byte[]> send(Session session, String method, String path, byte[] body, String type, String hash) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path));
        if(!method.equals("GET")) b.header("X-CSRF-TOKEN",session.csrf());
        if(type!=null) b.header("Content-Type",type);
        if(hash!=null) b.header("X-Chunk-SHA256",hash);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        return session.client().send(b.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    private HttpResponse<byte[]> json(Session s,String path,String body) throws Exception { return send(s,"POST",path,body.getBytes(StandardCharsets.UTF_8),"application/json",null); }
    private String text(HttpResponse<byte[]> r) { return new String(r.body(),StandardCharsets.UTF_8); }
    private String token(HttpResponse<byte[]> r) { var m=TOKEN.matcher(text(r)); assertTrue(m.find(),text(r)); return m.group(1); }
    private Session login(String name) throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var anonymous=new Session(client,""); String csrf=token(send(anonymous,"GET","auth/csrf",null,null,null));
        var r=json(new Session(client,csrf),"auth/login","{\"username\":\""+name+"\",\"password\":\"integration-password\"}");
        assertEquals(200,r.statusCode(),text(r)); return new Session(client,token(r));
    }
    private String digest(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private Upload create(Session s,String name, byte[] data) throws Exception {
        String req=UUID.randomUUID().toString(), sha=digest(data);
        String body="{\"parentId\":\"0\",\"name\":\""+name+"\",\"sizeBytes\":"+data.length+",\"requestId\":\""+req+"\",\"chunkHashes\":[\""+sha+"\"]}";
        var r=json(s,"uploads",body); assertEquals(200,r.statusCode(),text(r));
        String id=db.queryForObject("SELECT id FROM nd_multipart_upload WHERE request_id=?",String.class,req);
        return new Upload(id,sha,data);
    }
    private HttpResponse<byte[]> put(Session s,Upload u,byte[] data,String sha) throws Exception { return send(s,"PUT","uploads/"+u.id()+"/chunks/0",data,"application/octet-stream",sha); }
    private HttpResponse<byte[]> complete(Session s,Upload u,String resolution) throws Exception { return json(s,"uploads/"+u.id()+"/complete","{\"resolution\":\""+resolution+"\"}"); }
    private HttpResponse<byte[]> createSmall(Session s,String request) throws Exception {
        String body="{\"parentId\":\"0\",\"name\":\"limit-"+request+"\",\"sizeBytes\":1,\"requestId\":\""+request+"\",\"chunkHashes\":[\""+digest(new byte[]{1})+"\"]}";
        return json(s,"uploads",body);
    }
    private HttpResponse<byte[]> deleteNode(Session s,long id) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/nodes/"+id))
            .header("X-CSRF-TOKEN",s.csrf()).header("If-Match","\"v1\"").DELETE().build();
        return s.client().send(request,HttpResponse.BodyHandlers.ofByteArray());
    }
    @Test void idempotenceConcurrencyAndCleanup() throws Exception {
        long alice=Long.parseLong(System.getenv("NETDISK_QA_USER_ID"));
        db.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES(?,?,?,'ACTIVE')",alice,"alice",encoder.encode("integration-password"));
        Session a=login("alice");
        byte[] bytes=new byte[1024*1024+31]; new Random(113).nextBytes(bytes);
        String sha=digest(bytes), request=UUID.randomUUID().toString();
        String body="{\"parentId\":\"0\",\"name\":\"idempotent.bin\",\"sizeBytes\":"+bytes.length+",\"requestId\":\""+request+"\",\"chunkHashes\":[\""+sha+"\"]}";
        var pool=Executors.newFixedThreadPool(2); var gate=new CountDownLatch(1);
        String id;
        try {
            var f1=pool.submit(()->{gate.await();return json(a,"uploads",body);});
            var f2=pool.submit(()->{gate.await();return json(a,"uploads",body);});
            gate.countDown(); var r1=f1.get(); var r2=f2.get();
            assertEquals(200,r1.statusCode(),text(r1)); assertEquals(200,r2.statusCode(),text(r2));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM nd_multipart_upload WHERE request_id=?",Integer.class,request));
            id=db.queryForObject("SELECT id FROM nd_multipart_upload WHERE request_id=?",String.class,request);
            assertTrue(text(r1).contains(id)); assertTrue(text(r2).contains(id));
        } finally { pool.shutdownNow(); }
        String changed=body.replace(sha,"0".repeat(64));
        assertEquals(409,json(a,"uploads",changed).statusCode(),"a request id cannot be reused for different content");
        Upload job=new Upload(id,sha,bytes);
        var partPool=Executors.newFixedThreadPool(2); var partGate=new CountDownLatch(1);
        try {
            var f1=partPool.submit(()->{partGate.await();return put(a,job,bytes,sha);});
            var f2=partPool.submit(()->{partGate.await();return put(a,job,bytes,sha);});
            partGate.countDown(); var r1=f1.get(); var r2=f2.get();
            assertTrue(List.of(204,409,429).contains(r1.statusCode()),text(r1));
            assertTrue(List.of(204,409,429).contains(r2.statusCode()),text(r2));
            assertTrue(r1.statusCode()==204 || r2.statusCode()==204,"one writer must publish the part");
        } finally { partPool.shutdownNow(); }
        assertEquals(204,put(a,job,bytes,sha).statusCode());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM nd_multipart_part WHERE upload_id=? AND state='READY'",Integer.class,id));
        var first=complete(a,job,"FAIL"); assertEquals(200,first.statusCode(),text(first));
        var repeat=complete(a,job,"FAIL"); assertEquals(200,repeat.statusCode(),text(repeat));
        assertTrue(text(repeat).contains("COMPLETED"));
        long node=db.queryForObject("SELECT node_id FROM nd_multipart_upload WHERE id=?",Long.class,id);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE user_id=? AND name='idempotent.bin' AND status='READY'",Integer.class,alice));
        assertArrayEquals(bytes,send(a,"GET","nodes/"+node+"/content",null,null,null).body());
        assertEquals(200,json(a,"uploads",body).statusCode(),"replayed create returns original completed task");
        var folder=json(a,"folders","{\"parentId\":\"0\",\"name\":\"active-folder\"}");
        assertEquals(201,folder.statusCode(),text(folder));
        long folderId=db.queryForObject("SELECT id FROM nd_node WHERE user_id=? AND name='active-folder'",Long.class,alice);
        String folderReq=UUID.randomUUID().toString();
        String folderBody="{\"parentId\":\""+folderId+"\",\"name\":\"pending.bin\",\"sizeBytes\":"+bytes.length+",\"requestId\":\""+folderReq+"\",\"chunkHashes\":[\""+sha+"\"]}";
        assertEquals(200,json(a,"uploads",folderBody).statusCode());
        String folderJob=db.queryForObject("SELECT id FROM nd_multipart_upload WHERE request_id=?",String.class,folderReq);
        assertEquals(409,deleteNode(a,folderId).statusCode(),"cannot delete a folder with an active upload");
        assertEquals(200,json(a,"uploads/"+folderJob+"/cancel","{}").statusCode());
        assertEquals(202,deleteNode(a,folderId).statusCode(),"folder can be deleted after task cancellation");
        assertEquals(409,send(a,"PUT","uploads/"+folderJob+"/chunks/0",bytes,"application/octet-stream",sha).statusCode());
        Upload expired=create(a,"expired.bin",bytes);
        assertEquals(204,put(a,expired,bytes,expired.sha()).statusCode());
        String key=db.queryForObject("SELECT object_key FROM nd_multipart_part WHERE upload_id=? AND part_no=0",String.class,expired.id());
        assertTrue(storage.exists(key));
        db.update("UPDATE nd_multipart_upload SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",expired.id());
        gc.collect();
        assertEquals("SKIPPED",db.queryForObject("SELECT status FROM nd_multipart_upload WHERE id=?",String.class,expired.id()));
        assertTrue(storage.exists(key),"expired objects wait for the safe cleanup delay");
        db.update("UPDATE nd_storage_gc SET next_retry_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE storage_key=?",key);
        gc.collect(); assertFalse(storage.exists(key),"expired part must eventually be removed");
        assertEquals(409,put(a,expired,bytes,expired.sha()).statusCode());
        assertArrayEquals(bytes,send(a,"GET","nodes/"+node+"/content",null,null,null).body(),"GC must retain completed user files");
        System.out.println("PASS concurrent idempotent create, same-part concurrency, completed replay, active folder protection, cancelled/expired cleanup");
    }
}

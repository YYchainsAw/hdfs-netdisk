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
class RealMultipartIT {
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
    @Test void actualHdfsAndMysql() throws Exception {
        long alice=Long.parseLong(System.getenv("NETDISK_QA_USER_ID")), bob=alice+1, charlie=alice+2;
        db.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES(?,?,?,'ACTIVE'),(?,?,?,'ACTIVE')",alice,"alice",encoder.encode("integration-password"),bob,"bob",encoder.encode("integration-password"));
        db.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES(?,?,?,'ACTIVE')",charlie,"charlie",encoder.encode("integration-password"));
        Session a=login("alice"), b=login("bob"), c=login("charlie");
        byte[] bytes=new byte[1024*1024+13]; new Random(55).nextBytes(bytes);
        Upload u=create(a,"sample.bin",bytes);
        assertEquals(404,send(b,"GET","uploads/"+u.id(),null,null,null).statusCode());
        assertEquals(409,put(a,u,bytes,"0".repeat(64)).statusCode());
        assertEquals(204,put(a,u,bytes,u.sha()).statusCode());
        assertEquals(204,put(a,u,bytes,u.sha()).statusCode());
        var state=send(a,"GET","uploads/"+u.id(),null,null,null);
        assertEquals(200,state.statusCode(),text(state)); assertTrue(text(state).contains(u.sha()));
        var finished=complete(a,u,"FAIL"); assertEquals(200,finished.statusCode(),text(finished));
        Long node=db.queryForObject("SELECT node_id FROM nd_multipart_upload WHERE id=?",Long.class,u.id());
        var content=send(a,"GET","nodes/"+node+"/content",null,null,null);
        assertEquals(200,content.statusCode(),text(content)); assertArrayEquals(bytes,content.body());
        assertEquals(404,send(b,"GET","nodes/"+node+"/content",null,null,null).statusCode());
        var first=create(a,"conflict.bin",bytes); var second=create(a,"conflict.bin",bytes);
        assertEquals(204,put(a,first,bytes,first.sha()).statusCode()); assertEquals(204,put(a,second,bytes,second.sha()).statusCode());
        var gate=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var f1=pool.submit(()->{ gate.await(); return complete(a,first,"FAIL"); });
            var f2=pool.submit(()->{ gate.await(); return complete(a,second,"FAIL"); }); gate.countDown();
            var r1=f1.get(); var r2=f2.get();
            assertEquals(1,java.util.stream.Stream.of(r1.statusCode(),r2.statusCode()).filter(x->x==200).count());
            Upload loser=r1.statusCode()!=200?first:second;
            int loserStatus=r1.statusCode()!=200?r1.statusCode():r2.statusCode();
            assertTrue(loserStatus==409 || loserStatus==429,"loser status="+loserStatus);
            if(loserStatus==429) assertEquals(409,complete(a,loser,"FAIL").statusCode());
            assertEquals(200,complete(a,loser,"KEEP_BOTH").statusCode());
        } finally { pool.shutdownNow(); }
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE user_id=? AND status='READY' AND name LIKE 'conflict.bin%'",Integer.class,alice));
        byte[] firstChunk=new byte[8*1024*1024], secondChunk=new byte[113];
        new Random(73).nextBytes(firstChunk); new Random(74).nextBytes(secondChunk);
        String firstHash=digest(firstChunk), secondHash=digest(secondChunk), request=UUID.randomUUID().toString();
        String create="{\"parentId\":\"0\",\"name\":\"resumed.bin\",\"sizeBytes\":"+(firstChunk.length+secondChunk.length)+",\"requestId\":\""+request+"\",\"chunkHashes\":[\""+firstHash+"\",\""+secondHash+"\"]}";
        var initial=json(a,"uploads",create); assertEquals(200,initial.statusCode(),text(initial));
        String multipartId=db.queryForObject("SELECT id FROM nd_multipart_upload WHERE request_id=?",String.class,request);
        assertEquals(204,send(a,"PUT","uploads/"+multipartId+"/chunks/0",firstChunk,"application/octet-stream",firstHash).statusCode());
        Session reconnected=login("alice");
        var resumed=send(reconnected,"GET","uploads/"+multipartId,null,null,null);
        assertEquals(200,resumed.statusCode(),text(resumed)); assertTrue(text(resumed).contains(firstHash));
        assertEquals(204,send(reconnected,"PUT","uploads/"+multipartId+"/chunks/1",secondChunk,"application/octet-stream",secondHash).statusCode());
        var merged=json(reconnected,"uploads/"+multipartId+"/complete","{\"resolution\":\"FAIL\"}");
        assertEquals(200,merged.statusCode(),text(merged));
        Long mergedNode=db.queryForObject("SELECT node_id FROM nd_multipart_upload WHERE id=?",Long.class,multipartId);
        var joined=send(reconnected,"GET","nodes/"+mergedNode+"/content",null,null,null);
        byte[] expected=new byte[firstChunk.length+secondChunk.length];
        System.arraycopy(firstChunk,0,expected,0,firstChunk.length); System.arraycopy(secondChunk,0,expected,firstChunk.length,secondChunk.length);
        assertEquals(200,joined.statusCode()); assertArrayEquals(expected,joined.body());
        String completedChunk=db.queryForObject("SELECT object_key FROM nd_multipart_part WHERE upload_id=? AND part_no=0",String.class,u.id());
        String completedFile=db.queryForObject("SELECT storage_key FROM nd_node WHERE id=?",String.class,node);
        assertTrue(storage.exists(completedChunk)); assertTrue(storage.exists(completedFile));
        db.update("UPDATE nd_storage_gc SET next_retry_at=DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE storage_key=?",completedChunk);
        gc.collect(); assertFalse(storage.exists(completedChunk)); assertTrue(storage.exists(completedFile));
        Upload active=create(a,"active.bin",bytes);
        assertEquals(204,put(a,active,bytes,active.sha()).statusCode());
        String activeKey=db.queryForObject("SELECT object_key FROM nd_multipart_part WHERE upload_id=? AND part_no=0",String.class,active.id());
        db.update("INSERT IGNORE INTO nd_storage_gc(user_id,storage_key,reason,status,next_retry_at) VALUES (?,?,'ORPHAN','PENDING',DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND))",alice,activeKey);
        gc.collect(); assertTrue(storage.exists(activeKey),"GC must not remove an active upload part");
        db.update("UPDATE nd_multipart_part SET state='WRITING',token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 10 MINUTE) WHERE upload_id=? AND part_no=0",UUID.randomUUID().toString(),active.id());
        gc.unlockProcess(); gc.lockProcess();
        assertEquals(204,put(a,active,bytes,active.sha()).statusCode(),"startup must release an interrupted writer lease");
        assertEquals(200,complete(a,active,"FAIL").statusCode());
        Upload cancelled=create(a,"cancelled.bin",bytes);
        assertEquals(204,put(a,cancelled,bytes,cancelled.sha()).statusCode());
        assertEquals(404,json(b,"uploads/"+cancelled.id()+"/cancel","{}").statusCode());
        assertEquals(200,json(a,"uploads/"+cancelled.id()+"/cancel","{}").statusCode());
        assertEquals(200,json(a,"uploads/"+cancelled.id()+"/cancel","{}").statusCode());
        assertEquals(409,json(a,"uploads/"+u.id()+"/cancel","{}").statusCode(),"completed files cannot be cancelled");
        assertTrue(storage.remaining()>0);
        var quotaPool=Executors.newFixedThreadPool(4); var quotaGate=new CountDownLatch(1);
        String q1=UUID.randomUUID().toString(),q2=UUID.randomUUID().toString(),q3=UUID.randomUUID().toString(),q4=UUID.randomUUID().toString();
        try {
            var f1=quotaPool.submit(()->{quotaGate.await(); return createSmall(a,q1);});
            var f2=quotaPool.submit(()->{quotaGate.await(); return createSmall(a,q2);});
            var f3=quotaPool.submit(()->{quotaGate.await(); return createSmall(b,q3);});
            var f4=quotaPool.submit(()->{quotaGate.await(); return createSmall(b,q4);});
            quotaGate.countDown();
            for(var response:List.of(f1.get(),f2.get(),f3.get(),f4.get())) assertEquals(200,response.statusCode(),text(response));
        } finally { quotaPool.shutdownNow(); }
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM nd_multipart_upload WHERE status IN ('UPLOADING','FINALIZING')",Integer.class));
        assertEquals(429,createSmall(a,UUID.randomUUID().toString()).statusCode());
        assertEquals(429,createSmall(c,UUID.randomUUID().toString()).statusCode(),"global task limit applies across users");
        String quotaId=db.queryForObject("SELECT id FROM nd_multipart_upload WHERE request_id=?",String.class,q1);
        assertEquals(200,json(a,"uploads/"+quotaId+"/cancel","{}").statusCode());
        assertEquals(200,createSmall(a,UUID.randomUUID().toString()).statusCode());
        for(var row:db.queryForList("SELECT id,user_id FROM nd_multipart_upload WHERE status IN ('UPLOADING','FINALIZING')")) {
            Session owner=((Number)row.get("user_id")).longValue()==alice?a:b;
            assertEquals(200,json(owner,"uploads/"+row.get("id")+"/cancel","{}").statusCode());
        }
        String emptyRequest=UUID.randomUUID().toString();
        var zero=json(a,"uploads","{\"parentId\":\"0\",\"name\":\"空文件.txt\",\"sizeBytes\":0,\"requestId\":\""+emptyRequest+"\",\"chunkHashes\":[]}");
        assertEquals(200,zero.statusCode(),text(zero));
        String emptyId=db.queryForObject("SELECT id FROM nd_multipart_upload WHERE request_id=?",String.class,emptyRequest);
        var zeroFinished=json(a,"uploads/"+emptyId+"/complete","{\"resolution\":\"FAIL\"}");
        assertEquals(200,zeroFinished.statusCode(),text(zeroFinished));
        Long zeroNode=db.queryForObject("SELECT node_id FROM nd_multipart_upload WHERE id=?",Long.class,emptyId);
        var zeroDownload=send(a,"GET","nodes/"+zeroNode+"/content",null,null,null);
        assertEquals(200,zeroDownload.statusCode()); assertEquals(0,zeroDownload.body().length);
        HdfsStorage low=org.mockito.Mockito.spy(storage);
        org.mockito.Mockito.doReturn(0L).when(low).remaining();
        var lowService=new MultipartService(db,tx,nodes,low,"/netdisk/temp/"+System.getenv("NETDISK_QA_ROOT"),"/netdisk/users/"+System.getenv("NETDISK_QA_ROOT"));
        var lowCreate=assertThrows(NodeService.Problem.class,()->lowService.create(alice,"0","low.bin",bytes.length,UUID.randomUUID().toString(),List.of(digest(bytes))));
        assertEquals(507,lowCreate.status);
        Upload lowJob=create(a,"capacity-check.bin",bytes);
        var lowWrite=assertThrows(NodeService.Problem.class,()->lowService.put(alice,lowJob.id(),0,lowJob.sha(),bytes.length,new ByteArrayInputStream(bytes)));
        assertEquals(507,lowWrite.status); assertEquals(204,put(a,lowJob,bytes,lowJob.sha()).statusCode());
        var lowComplete=assertThrows(NodeService.Problem.class,()->lowService.complete(alice,lowJob.id(),"FAIL"));
        assertEquals(507,lowComplete.status); assertEquals(200,complete(a,lowJob,"FAIL").statusCode());
    }
}

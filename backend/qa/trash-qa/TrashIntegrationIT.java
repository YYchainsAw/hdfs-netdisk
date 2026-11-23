package xyz.yychainsaw.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.test.context.ActiveProfiles;
import xyz.yychainsaw.backend.storage.HdfsStorage;
import xyz.yychainsaw.backend.repository.NdNodeMapper;

@ActiveProfiles("server")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
 "spring.datasource.url=${NETDISK_QA_DB_URL}","spring.datasource.username=root",
 "spring.datasource.password=${NETDISK_QA_DB_PASSWORD}","spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
 "netdisk.hdfs.temp-root=/netdisk/temp/${NETDISK_QA_ROOT}",
 "netdisk.hdfs.users-root=/netdisk/users/${NETDISK_QA_ROOT}"
})
class TrashIntegrationIT {
 @Autowired JdbcTemplate db;
 @Autowired NodeService nodes;
 @Autowired TrashService trash;
 @Autowired MultipartGarbageCollector gc;
 @Autowired HdfsStorage hdfs;
 @Autowired MultipartService uploads;
 @Autowired TransactionTemplate tx;
 @Autowired NdNodeMapper nodeMapper;
 @Test void nestedConflictIsolationAndActualHdfsPurge() throws Exception {
  db.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES(777,'trash-user','unused','ACTIVE'),(778,'trash-user2','unused','ACTIVE')");
  var folder=nodes.create(777,0,"top");
  var child=nodes.create(777,folder.getId(),"child");
  String base="/netdisk/users/"+System.getenv("NETDISK_QA_ROOT")+"/777";
  hdfs.mkdirs(base);
  String key=base+"/"+UUID.randomUUID(); byte[] content="trash contents".getBytes(StandardCharsets.UTF_8);
  hdfs.write(key,new ByteArrayInputStream(content),content.length);
  db.update("INSERT INTO nd_node(user_id,parent_id,name,node_type,status,storage_key,size_bytes,version) VALUES(777,?,'a.txt','FILE','READY',?,?,1)",child.getId(),key,content.length);
  long file=db.queryForObject("SELECT id FROM nd_node WHERE storage_key=?",Long.class,key);
  assertEquals(404,assertThrows(NodeService.Problem.class,()->trash.trash(778,folder.getId(),1)).status);
  trash.trash(777,folder.getId(),1);
  assertEquals(content.length,nodeMapper.sumRetainedFileBytes(777).intValueExact()); assertEquals(1,nodeMapper.countRetainedFiles(777));
  assertTrue(hdfs.exists(key)); assertEquals(0,nodes.list(777,0,"",50,0).size());
  assertEquals(1,trash.list(777,50,0).size()); assertEquals(0,trash.list(778,50,0).size());
  assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE trash_id=? AND status='TRASHED'",Integer.class,folder.getId()));
  assertEquals(404,assertThrows(NodeService.Problem.class,()->trash.restore(778,folder.getId(),2)).status);
  assertEquals(412,assertThrows(NodeService.Problem.class,()->trash.restore(777,folder.getId(),1)).status);
  var collision=nodes.create(777,0,"top");
  assertEquals(409,assertThrows(NodeService.Problem.class,()->trash.restore(777,folder.getId(),2)).status);
  assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE trash_id=? AND status='TRASHED'",Integer.class,folder.getId()));
  trash.trash(777,collision.getId(),1);
  trash.restore(777,folder.getId(),2);
  assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE id IN (?,?,?) AND status='READY'",Integer.class,folder.getId(),child.getId(),file));
  trash.trash(777,folder.getId(),3);
  trash.purge(777,folder.getId(),4);
  assertTrue(hdfs.exists(key));
  assertEquals("DELETING",db.queryForObject("SELECT status FROM nd_node WHERE id=?",String.class,file));
  assertEquals(content.length,nodeMapper.sumRetainedFileBytes(777).intValueExact());
  gc.collect(); assertFalse(hdfs.exists(key));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE id=?",Integer.class,file));
  assertEquals(0,nodeMapper.sumRetainedFileBytes(777).intValueExact());
  assertEquals(1,trash.list(777,50,0).size());
 }
 @Test void concurrentNewChildAndTrashNeverOrphanVisibleNodes() throws Exception {
  db.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES(779,'race-trash','unused','ACTIVE')");
  for(int n=0;n<8;n++) {
   var parent=nodes.create(779,0,"parent"+n); var gate=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
   try {
    var a=pool.submit(()->{gate.await();try{trash.trash(779,parent.getId(),1);return 202;}catch(NodeService.Problem e){return e.status;}catch(ConcurrencyFailureException e){return 409;}});
    var b=pool.submit(()->{gate.await();try{nodes.create(779,parent.getId(),"new");return 201;}catch(NodeService.Problem e){return e.status;}catch(ConcurrencyFailureException e){return 409;}});
    gate.countDown(); int x=a.get(),y=b.get();
    assertTrue(Set.of(202,409).contains(x),"trash="+x); assertTrue(Set.of(201,404,409).contains(y),"create="+y);
    if(x==202) assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE user_id=779 AND parent_id=? AND status='READY'",Integer.class,parent.getId()));
    else assertEquals("READY",db.queryForObject("SELECT status FROM nd_node WHERE id=?",String.class,parent.getId()));
   } finally {pool.shutdownNow();}
  }
 }
 @Test void trashRejectsActiveUploadAndConcurrentCreationDoesNotOrphan() throws Exception {
  db.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES(780,'race-upload-trash','unused','ACTIVE')");
  for(int n=0;n<8;n++) {
   var parent=nodes.create(780,0,"upload-parent"+n); var gate=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
   try {
    var a=pool.submit(()->{gate.await();try{trash.trash(780,parent.getId(),1);return 202;}catch(NodeService.Problem e){return e.status;}catch(ConcurrencyFailureException e){return 409;}});
    var b=pool.submit(()->{gate.await();try{uploads.create(780,parent.getId().toString(),"empty.txt",0,UUID.randomUUID().toString(),java.util.List.of());return 201;}catch(NodeService.Problem e){return e.status;}catch(ConcurrencyFailureException e){return 409;}});
    gate.countDown(); int x=a.get(),y=b.get();
    assertTrue(Set.of(202,409).contains(x),"trash="+x); assertTrue(Set.of(201,404,409).contains(y),"upload="+y);
    if(x==202) assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM nd_multipart_upload WHERE user_id=780 AND parent_id=? AND status IN ('UPLOADING','FINALIZING')",Integer.class,parent.getId()));
    if(y==201) {
     assertEquals("READY",db.queryForObject("SELECT status FROM nd_node WHERE id=?",String.class,parent.getId()));
     String upload=db.queryForObject("SELECT id FROM nd_multipart_upload WHERE user_id=780 AND parent_id=? AND status='UPLOADING'",String.class,parent.getId());
     uploads.cancel(780,upload);
    }
   } finally {pool.shutdownNow();}
  }
 }
 @Test void failedHdfsPurgeRemainsRecoverableAndRetries() throws Exception {
  db.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES(781,'trash-gc','unused','ACTIVE')");
  String root="/netdisk/users/"+System.getenv("NETDISK_QA_ROOT"); String base=root+"/781"; hdfs.mkdirs(base);
  String key=base+"/"+UUID.randomUUID(); hdfs.write(key,new ByteArrayInputStream(new byte[]{1,2,3}),3);
  db.update("INSERT INTO nd_node(user_id,parent_id,name,node_type,status,storage_key,size_bytes,version) VALUES(781,0,'failure','FILE','READY',?,3,1)",key);
  long id=db.queryForObject("SELECT id FROM nd_node WHERE storage_key=?",Long.class,key);
  trash.trash(781,id,1); trash.purge(781,id,2);
  var spy=Mockito.spy(hdfs); Mockito.doThrow(new IOException("injected unavailable")).when(spy).delete(key);
  var localGc=new MultipartGarbageCollector(db,tx,spy,"/netdisk/temp/"+System.getenv("NETDISK_QA_ROOT"),root);
  localGc.collect(); assertTrue(hdfs.exists(key));
  assertEquals("DELETING",db.queryForObject("SELECT status FROM nd_node WHERE id=?",String.class,id));
  assertEquals("PENDING",db.queryForObject("SELECT status FROM nd_storage_gc WHERE storage_key=?",String.class,key));
  db.update("UPDATE nd_storage_gc SET next_retry_at=CURRENT_TIMESTAMP WHERE storage_key=?",key);
  gc.collect(); assertFalse(hdfs.exists(key));
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE id=?",Integer.class,id));
 }

 @Test void uploadedNameAfterTrashAndAutomaticNumberingRespectLiveNamespace() throws Exception {
  db.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES(782,'trash-upload','unused','ACTIVE')");
  var existing=nodes.create(782,0,"test.txt"); trash.trash(782,existing.getId(),1);
  var first=uploads.create(782,"0","test.txt",0,UUID.randomUUID().toString(),java.util.List.of());
  String task=first.get("id").toString();
  var done=uploads.complete(782,task,"FAIL"); assertEquals("COMPLETED",done.get("status"));
  assertEquals(409,assertThrows(NodeService.Problem.class,()->trash.restore(782,existing.getId(),2)).status);
  var second=uploads.create(782,"0","test.txt",0,UUID.randomUUID().toString(),java.util.List.of());
  var secondDone=uploads.complete(782,second.get("id").toString(),"KEEP_BOTH"); assertEquals("COMPLETED",secondDone.get("status"));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE user_id=782 AND name='test (1).txt' AND status='READY'",Integer.class));
 }

 @Test void simultaneousRestoreAndSameNameUploadHaveOnePublishedWinner() throws Exception {
  db.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES(783,'restore-upload-race','unused','ACTIVE')");
  for(int n=0;n<6;n++) {
   String name="race"+n+".txt"; var folder=nodes.create(783,0,name); trash.trash(783,folder.getId(),1);
   var task=uploads.create(783,"0",name,0,UUID.randomUUID().toString(),java.util.List.of());
   var gate=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
   try {
    var a=pool.submit(()->{gate.await();try{trash.restore(783,folder.getId(),2);return 200;}catch(NodeService.Problem e){return e.status;}catch(ConcurrencyFailureException e){return 409;}});
    var b=pool.submit(()->{gate.await();try{uploads.complete(783,task.get("id").toString(),"FAIL");return 200;}catch(NodeService.Problem e){return e.status;}catch(ConcurrencyFailureException e){return 409;}});
    gate.countDown();int x=a.get(),y=b.get();assertTrue(Set.of(200,409).contains(x),"restore="+x);assertTrue(Set.of(200,409).contains(y),"upload="+y);
    assertFalse(x==200 && y==200,"same active name cannot be published twice");
    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE user_id=783 AND parent_id=0 AND name=? AND status='READY'",Integer.class,name));
    String upload=task.get("id").toString();String status=db.queryForObject("SELECT status FROM nd_multipart_upload WHERE id=?",String.class,upload);
    if("UPLOADING".equals(status)) uploads.cancel(783,upload);
   } finally {pool.shutdownNow();}
  }
 }

}

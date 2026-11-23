package xyz.yychainsaw.backend.service;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={
 "spring.datasource.url=${NETDISK_QA_DB_URL}","spring.datasource.username=root",
 "spring.datasource.password=${NETDISK_QA_DB_PASSWORD}","spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"})
class NodeBatchMySqlIT {
 @Autowired JdbcTemplate db; @Autowired NodeService nodes; @Autowired NodeBatchService moves;
 private NodeBatchService.Entry e(long id,long v){return new NodeBatchService.Entry(Long.toString(id),Long.toString(v));}
 @BeforeEach void clean(){db.update("DELETE FROM nd_node");db.update("DELETE FROM nd_user");db.update("INSERT INTO nd_user(id,username,password_hash,status) VALUES(1,'alice','hash','ACTIVE'),(2,'bob','hash','ACTIVE')");}
 @Test void batchCollisionRollsBackAndOwnerIsolated(){
  var dest=nodes.create(1,0,"dest");var a=nodes.create(1,0,"a");var b=nodes.create(1,0,"b");nodes.create(1,dest.getId(),"b");
  assertEquals(409,assertThrows(NodeService.Problem.class,()->moves.move(1,dest.getId().toString(),List.of(e(a.getId(),1),e(b.getId(),1)))).status);
  assertEquals(0L,db.queryForObject("SELECT parent_id FROM nd_node WHERE id=?",Long.class,a.getId()));
  assertEquals(404,assertThrows(NodeService.Problem.class,()->moves.move(2,"0",List.of(e(a.getId(),1)))).status);
 }
 @Test void cyclesAndStaleVersionsRejected(){
  var a=nodes.create(1,0,"a");var b=nodes.create(1,a.getId(),"b");
  assertEquals(409,assertThrows(NodeService.Problem.class,()->moves.move(1,b.getId().toString(),List.of(e(a.getId(),1)))).status);
  assertEquals(412,assertThrows(NodeService.Problem.class,()->moves.move(1,"0",List.of(e(b.getId(),2)))).status);
 }
 @Test void parallelMovesPreserveUniqueName() throws Exception {
  var a=nodes.create(1,0,"a");var b=nodes.create(1,0,"b");var target=nodes.create(1,0,"target");
  var x=nodes.create(1,a.getId(),"same");var y=nodes.create(1,b.getId(),"same");
  var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
  try {
   var f=pool.submit(()->{gate.await();try{moves.move(1,target.getId().toString(),List.of(e(x.getId(),1)));return 200;}catch(NodeService.Problem ex){return ex.status;}});
   var g=pool.submit(()->{gate.await();try{moves.move(1,target.getId().toString(),List.of(e(y.getId(),1)));return 200;}catch(NodeService.Problem ex){return ex.status;}});
   gate.countDown();assertEquals(Set.of(200,409),Set.of(f.get(20,TimeUnit.SECONDS),g.get(20,TimeUnit.SECONDS)));
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM nd_node WHERE parent_id=? AND name='same'",Integer.class,target.getId()));
  } finally {pool.shutdownNow();}
 }
}

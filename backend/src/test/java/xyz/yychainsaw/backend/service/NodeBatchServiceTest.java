package xyz.yychainsaw.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={
 "spring.datasource.url=jdbc:h2:mem:move_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="})
class NodeBatchServiceTest {
 @Autowired JdbcTemplate db;
 @Autowired NodeService nodes;
 @Autowired NodeBatchService moves;
 private NodeBatchService.Entry e(long id,long version){return new NodeBatchService.Entry(Long.toString(id),Long.toString(version));}
 @BeforeEach void reset(){
  db.execute("CREATE TABLE IF NOT EXISTS nd_user(id BIGINT PRIMARY KEY,username VARCHAR(64),password_hash VARCHAR(255),status VARCHAR(16))");
  db.execute("CREATE TABLE IF NOT EXISTS nd_node(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,parent_id BIGINT NOT NULL,name VARCHAR(191) NOT NULL,node_type VARCHAR(8) NOT NULL,status VARCHAR(16) NOT NULL,storage_key VARCHAR(255),sha256 CHAR(64),size_bytes BIGINT,version BIGINT NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(user_id,parent_id,name))");
  db.update("DELETE FROM nd_node");db.update("DELETE FROM nd_user");db.update("INSERT INTO nd_user VALUES(1,'alice','hash','ACTIVE'),(2,'bob','hash','ACTIVE')");
 }
 @Test void movesSeveralNodesWithOwnershipAndVersion(){
  var target=nodes.create(1,0,"target");var a=nodes.create(1,0,"a");var b=nodes.create(1,0,"b");
  assertEquals(404,assertThrows(NodeService.Problem.class,()->moves.move(2,"0",List.of(e(a.getId(),1)))).status);
  assertEquals(412,assertThrows(NodeService.Problem.class,()->moves.move(1,target.getId().toString(),List.of(e(a.getId(),2)))).status);
  moves.move(1,target.getId().toString(),List.of(e(a.getId(),1),e(b.getId(),1)));
  var children=nodes.list(1,target.getId(),"",50,0);assertEquals(2,children.size());assertTrue(children.stream().allMatch(n->n.getVersion()==2));
 }
 @Test void collisionRollsBackWholeBatch(){
  var target=nodes.create(1,0,"target");var a=nodes.create(1,0,"a");var b=nodes.create(1,0,"b");nodes.create(1,target.getId(),"b");
  assertEquals(409,assertThrows(NodeService.Problem.class,()->moves.move(1,target.getId().toString(),List.of(e(a.getId(),1),e(b.getId(),1)))).status);
  assertEquals(0L,db.queryForObject("SELECT parent_id FROM nd_node WHERE id=?",Long.class,a.getId()));
  assertEquals(1L,db.queryForObject("SELECT version FROM nd_node WHERE id=?",Long.class,a.getId()));
 }
 @Test void rejectsCyclesAndCrossUserTargets(){
  var parent=nodes.create(1,0,"parent");var child=nodes.create(1,parent.getId(),"child");var foreign=nodes.create(2,0,"other");
  assertEquals(409,assertThrows(NodeService.Problem.class,()->moves.move(1,child.getId().toString(),List.of(e(parent.getId(),1)))).status);
  assertEquals(404,assertThrows(NodeService.Problem.class,()->moves.move(1,foreign.getId().toString(),List.of(e(parent.getId(),1)))).status);
 }
}

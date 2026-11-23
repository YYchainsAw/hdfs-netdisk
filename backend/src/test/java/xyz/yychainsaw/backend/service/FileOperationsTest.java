package xyz.yychainsaw.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={
    "spring.datasource.url=jdbc:h2:mem:fileops_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class FileOperationsTest {
    @Autowired JdbcTemplate db;
    @Autowired NodeService service;
    @BeforeEach void reset() {
        db.execute("CREATE TABLE IF NOT EXISTS nd_user(id BIGINT PRIMARY KEY,username VARCHAR(64),password_hash VARCHAR(255),status VARCHAR(16))");
        db.execute("CREATE TABLE IF NOT EXISTS nd_node(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,parent_id BIGINT NOT NULL,name VARCHAR(191) NOT NULL,node_type VARCHAR(8) NOT NULL,status VARCHAR(16) NOT NULL,storage_key VARCHAR(255) UNIQUE,sha256 CHAR(64),size_bytes BIGINT,version BIGINT NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(user_id,parent_id,name))");
        db.execute("CREATE TABLE IF NOT EXISTS nd_multipart_upload(id VARCHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,parent_id BIGINT NOT NULL,status VARCHAR(16) NOT NULL,node_id BIGINT,CONSTRAINT fk_test_node FOREIGN KEY(node_id) REFERENCES nd_node(id))");
        db.execute("CREATE TABLE IF NOT EXISTS nd_storage_gc(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,storage_key VARCHAR(255) UNIQUE,reason VARCHAR(24),status VARCHAR(16),next_retry_at TIMESTAMP)");
        db.update("DELETE FROM nd_storage_gc"); db.update("DELETE FROM nd_multipart_upload"); db.update("DELETE FROM nd_node"); db.update("DELETE FROM nd_user");
        db.update("INSERT INTO nd_user VALUES(1,'alice','hash','ACTIVE'),(2,'bob','hash','ACTIVE')");
    }
    @Test void renameChecksVersionOwnershipAndNames() {
        var a=service.create(1,0,"one"); var b=service.create(1,0,"two");
        assertEquals(404, assertThrows(NodeService.Problem.class,()->service.rename(2,a.getId(),1,"x")).status);
        assertEquals(409, assertThrows(NodeService.Problem.class,()->service.rename(1,a.getId(),1,"two")).status);
        var changed=service.rename(1,a.getId(),1,"Cafe\u0301");
        assertEquals("Café",changed.getName()); assertEquals(2L,changed.getVersion());
        assertEquals(412, assertThrows(NodeService.Problem.class,()->service.rename(1,a.getId(),1,"stale")).status);
        assertEquals(428, assertThrows(NodeService.Problem.class,()->NodeService.expectedVersion(null)).status);
        assertEquals(2,NodeService.expectedVersion("\"v2\""));
        assertEquals(400,assertThrows(NodeService.Problem.class,()->NodeService.expectedVersion("v2")).status);
    }
    @Test void concurrentRenamesToSameNameHaveOneWinner() throws Exception {
        var a=service.create(1,0,"a"); var b=service.create(1,0,"b");
        var pool=Executors.newFixedThreadPool(2); var gate=new CountDownLatch(1);
        try {
            var x=pool.submit(()->{gate.await(); try {service.rename(1,a.getId(),1,"same"); return 200;} catch(NodeService.Problem p){return p.status;}});
            var y=pool.submit(()->{gate.await(); try {service.rename(1,b.getId(),1,"same"); return 200;} catch(NodeService.Problem p){return p.status;}});
            gate.countDown(); assertEquals(Set.of(200,409),Set.of(x.get(),y.get()));
        } finally {pool.shutdownNow();}
    }
}

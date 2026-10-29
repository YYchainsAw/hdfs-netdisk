package xyz.yychainsaw.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:nodes_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class NodeFlowTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired NodeService service;

    @BeforeEach void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS nd_user (id BIGINT PRIMARY KEY, username VARCHAR(64), password_hash VARCHAR(255), status VARCHAR(16))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS nd_node (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, parent_id BIGINT NOT NULL, name VARCHAR(191) NOT NULL, node_type VARCHAR(8) NOT NULL, status VARCHAR(16) NOT NULL, size_bytes BIGINT, version BIGINT NOT NULL DEFAULT 1, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(user_id,parent_id,name))");
        jdbc.update("DELETE FROM nd_node"); jdbc.update("DELETE FROM nd_user");
        jdbc.update("INSERT INTO nd_user VALUES(1,'alice','hash','ACTIVE'),(2,'bob','hash','ACTIVE')");
    }
    @Test void foldersAreIsolatedNormalizedAndHierarchical() {
        var folder = service.create(1, 0, "Cafe\u0301");
        assertEquals("Café", folder.getName());
        assertEquals(409, assertThrows(NodeService.Problem.class, () -> service.create(1, 0, "Café")).status);
        assertEquals(404, assertThrows(NodeService.Problem.class, () -> service.list(2, folder.getId(), "", 50, 0)).status);
        assertEquals(404, assertThrows(NodeService.Problem.class, () -> service.create(2, folder.getId(), "private")).status);
        assertEquals(400, assertThrows(NodeService.Problem.class, () -> service.create(1, 0, "../abc")).status);
        service.create(2, 0, "Café");
        service.create(1, folder.getId(), "inside");
        assertEquals(1, service.list(1, folder.getId(), "inside", 50, 0).size());
        assertEquals(0, service.list(1, 0, "inside", 50, 0).size());
    }
    @Test void simultaneousSameNameOnlyOneSucceeds() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> create = () -> { gate.await(); try { service.create(1, 0, "report"); return 201; } catch (NodeService.Problem p) { return p.status; } };
            Future<Integer> a = pool.submit(create); Future<Integer> b = pool.submit(create); gate.countDown();
            assertEquals(java.util.Set.of(201, 409), java.util.Set.of(a.get(), b.get()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM nd_node WHERE user_id=1 AND parent_id=0 AND name='report'", Integer.class));
        } finally { pool.shutdownNow(); }
    }
}

package xyz.yychainsaw.backend.repository;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:download_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class DownloadMapperTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DownloadMapper mapper;
    @Test void onlyOwnedReadyFileCanBeQueried() {
        jdbc.execute("CREATE TABLE nd_node (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, parent_id BIGINT NOT NULL, name VARCHAR(191), node_type VARCHAR(8), status VARCHAR(16), size_bytes BIGINT, storage_key VARCHAR(255), version BIGINT, updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO nd_node (id,user_id,parent_id,name,node_type,status,size_bytes,storage_key,version) VALUES (1,1,0,'file','FILE','READY',3,'/netdisk/users/1/abc',1), (2,1,0,'pending','FILE','PENDING',3,'/netdisk/users/1/def',1), (3,1,0,'folder','FOLDER','READY',NULL,NULL,1)");
        var file = mapper.findVisibleFile(1, 1);
        assertNotNull(file);
        assertEquals("/netdisk/users/1/abc", file.getStorageKey());
        assertNull(mapper.findVisibleFile(2, 1));
        assertNull(mapper.findVisibleFile(1, 2));
        assertNull(mapper.findVisibleFile(1, 3));
    }
}

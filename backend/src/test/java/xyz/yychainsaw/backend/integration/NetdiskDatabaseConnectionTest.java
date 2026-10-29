package xyz.yychainsaw.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Explicit opt-in: reads the real netdisk schema without changing data. */
@EnabledIfEnvironmentVariable(named = "NETDISK_DB_INTEGRATION_TEST", matches = "true")
@SpringBootTest
@ActiveProfiles("server")
class NetdiskDatabaseConnectionTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void connectsToTheDedicatedSchemaAndCanReadItsTables() {
        assertEquals("netdisk", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name IN "
                + "('nd_user','nd_node','nd_upload','nd_storage_gc')", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM nd_user", Long.class));
    }
}

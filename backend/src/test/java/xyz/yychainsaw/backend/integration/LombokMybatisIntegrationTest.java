package xyz.yychainsaw.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = LombokMybatisIntegrationTest.TestApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:netdisk_smoke;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
    }
)
class LombokMybatisIntegrationTest {
    @Autowired
    private SmokeMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS integration_smoke (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        jdbc.update("DELETE FROM integration_smoke");
    }

    @Test
    void generatedAccessorsAndMapperPersistAndReadData() {
        SmokeRecord original = new SmokeRecord();
        original.setId(42L);
        original.setName("integration test");

        assertEquals(1, mapper.insert(original));
        SmokeRecord saved = mapper.selectById(42L);
        assertNotNull(saved);
        assertEquals(42L, saved.getId());
        assertEquals("integration test", saved.getName());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = SmokeMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
    static class TestApplication {
    }
}

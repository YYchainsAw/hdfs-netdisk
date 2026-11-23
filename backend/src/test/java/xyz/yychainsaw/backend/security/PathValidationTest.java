package xyz.yychainsaw.backend.security;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.backend.service.NodeService;
import xyz.yychainsaw.backend.storage.HdfsStorage;

class PathValidationTest {
    @Test void userFileNamesRejectTraversalAndControls() {
        for (String name : List.of("../x", "a/b", ".", "..", "a\u0000b", "a\nb"))
            assertThrows(NodeService.Problem.class, () -> NodeService.validName(name));
        assertEquals("demo.txt", NodeService.validName("demo.txt"));
    }
    @Test void storageKeysRejectEscapesAndUriInjectionBeforeAnyNetworkRequest() {
        HdfsStorage storage = new HdfsStorage(1, 1);
        for (String key : List.of("/netdisk/temp/../users/1/a", "/netdisk/temp/%2e%2e/a", "/netdisk/users/1/a?op=DELETE", "http://evil.example/", "/etc/passwd", "/netdisk/temp/x#fragment")) {
            IOException ex = assertThrows(IOException.class, () -> storage.exists(key));
            assertTrue(ex.getMessage().contains("Invalid"), key);
        }
    }
}

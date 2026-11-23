package xyz.yychainsaw.backend.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.backend.model.NdNode;
import xyz.yychainsaw.backend.repository.DownloadMapper;
import xyz.yychainsaw.backend.security.SessionUser;
import xyz.yychainsaw.backend.service.NodeService;
import xyz.yychainsaw.backend.storage.HdfsStorage;

@RestController
@RequestMapping("/api/v1/nodes")
public class DownloadController {
    private final DownloadMapper files;
    private final HdfsStorage storage;
    public DownloadController(DownloadMapper files, HdfsStorage storage) {
        this.files = files;
        this.storage = storage;
    }

    @GetMapping("/{id}/content")
    public void download(@PathVariable String id, Authentication auth, HttpServletResponse response) throws IOException {
        long userId = NodeService.id(((SessionUser) auth.getPrincipal()).id(), false);
        NdNode file = files.findVisibleFile(userId, NodeService.id(id, false));
        if (file == null) throw new NodeService.Problem(404, "NOT_FOUND", "文件不存在或不可访问");
        // Open before sending headers so unavailable HDFS produces an error, not a false success.
        InputStream in;
        try { in = storage.open(file.getStorageKey()); }
        catch (IOException e) { throw new NodeService.Problem(503, "STORAGE_UNAVAILABLE", "存储服务暂不可用，请稍后重试"); }
        try (in) {
            response.setStatus(200);
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.getName(), StandardCharsets.UTF_8).build().toString());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setContentLengthLong(file.getSizeBytes());
            in.transferTo(response.getOutputStream());
        }
    }

    @ExceptionHandler(NodeService.Problem.class)
    public ResponseEntity<?> handle(NodeService.Problem p) {
        return ResponseEntity.status(p.status).cacheControl(CacheControl.noStore())
            .body(Map.of("error", Map.of("code", p.code, "message", p.getMessage())));
    }
}

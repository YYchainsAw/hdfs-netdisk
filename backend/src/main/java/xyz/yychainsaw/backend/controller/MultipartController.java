package xyz.yychainsaw.backend.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import xyz.yychainsaw.backend.security.SessionUser;
import xyz.yychainsaw.backend.service.MultipartService;
import xyz.yychainsaw.backend.service.NodeService;
import xyz.yychainsaw.backend.service.UploadPolicy;

@RestController
@Profile("server")
@RequestMapping("/api/v1/uploads")
public class MultipartController {
    private final MultipartService service;
    public MultipartController(MultipartService service) { this.service=service; }
    public record CreateRequest(String parentId,String name,Long sizeBytes,String requestId,List<String> chunkHashes) {}
    public record CompleteRequest(String resolution) {}
    private long user(Authentication auth) { return NodeService.id(((SessionUser) auth.getPrincipal()).id(),false); }
    @GetMapping("/policy")
    public ResponseEntity<?> policy() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data", Map.of(
            "allowedExtensions", UploadPolicy.allowedExtensions(), "maxSizeBytes", Long.toString(UploadPolicy.MAX_SIZE_BYTES))));
    }
    @PostMapping
    public ResponseEntity<?> create(Authentication auth,@RequestBody CreateRequest request) {
        if(request.sizeBytes()==null) throw new NodeService.Problem(400,"INVALID_ARGUMENT","文件大小无效");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data",service.create(user(auth),request.parentId(),request.name(),request.sizeBytes(),request.requestId(),request.chunkHashes())));
    }
    @GetMapping("/{id}")
    public ResponseEntity<?> status(Authentication auth,@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data",service.status(user(auth),id)));
    }
    @PutMapping(value="/{id}/chunks/{index}",consumes="application/octet-stream")
    public ResponseEntity<?> put(Authentication auth,@PathVariable String id,@PathVariable int index,
        @RequestHeader("X-Chunk-SHA256") String sha,HttpServletRequest request) throws IOException {
        service.put(user(auth),id,index,sha,request.getContentLengthLong(),request.getInputStream());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/{id}/complete")
    public ResponseEntity<?> complete(Authentication auth,@PathVariable String id,@RequestBody CompleteRequest request) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data",service.complete(user(auth),id,request.resolution())));
    }
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(Authentication auth,@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data",service.cancel(user(auth),id)));
    }
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<?> concurrent(ConcurrencyFailureException ex) {
        return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(Map.of("error",Map.of("code","CONCURRENT_CHANGE","message","操作与其他请求冲突，请刷新后重试")));
    }
    @ExceptionHandler(NodeService.Problem.class)
    public ResponseEntity<?> problem(NodeService.Problem p) {
        return ResponseEntity.status(p.status).cacheControl(CacheControl.noStore()).body(Map.of("error",Map.of("code",p.code,"message",p.getMessage())));
    }
    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> storageError(IOException e) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("error",Map.of("code","STORAGE_UNAVAILABLE","message","存储暂不可用，请稍后重试")));
    }
}

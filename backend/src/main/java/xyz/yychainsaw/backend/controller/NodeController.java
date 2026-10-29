package xyz.yychainsaw.backend.controller;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.backend.model.NdNode;
import xyz.yychainsaw.backend.security.SessionUser;
import xyz.yychainsaw.backend.service.NodeService;

@RestController @RequestMapping("/api/v1")
public class NodeController {
    private final NodeService service;
    public NodeController(NodeService service) { this.service = service; }
    public record FolderRequest(String parentId, String name) {}
    public record NodeResponse(String id, String parentId, String name, String type, String sizeBytes, String version, String updatedAt) {}
    private NodeResponse response(NdNode node) {
        return new NodeResponse(node.getId().toString(), node.getParentId().toString(), node.getName(), node.getNodeType(),
            node.getSizeBytes() == null ? null : node.getSizeBytes().toString(), node.getVersion().toString(),
            node.getUpdatedAt() == null ? null : node.getUpdatedAt().atOffset(ZoneOffset.UTC).toString());
    }
    private long user(Authentication auth) { return NodeService.id(((SessionUser) auth.getPrincipal()).id(), false); }
    @GetMapping("/nodes")
    public ResponseEntity<?> list(Authentication auth, @RequestParam String parentId,
        @RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "50") int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new NodeService.Problem(400, "INVALID_ARGUMENT", "分页参数无效");
        long offset = (long) (page - 1) * pageSize;
        List<NdNode> rows = service.list(user(auth), NodeService.id(parentId, true), keyword, pageSize, offset);
        boolean hasMore = rows.size() > pageSize;
        List<NodeResponse> items = rows.subList(0, Math.min(rows.size(), pageSize)).stream().map(this::response).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data", Map.of("items",items,"page",page,"pageSize",pageSize,"hasMore",hasMore)));
    }
    @PostMapping("/folders")
    public ResponseEntity<?> create(Authentication auth, @RequestBody FolderRequest body) {
        NdNode node = service.create(user(auth), NodeService.id(body.parentId(), true), body.name());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).eTag("\"v" + node.getVersion() + "\"").body(Map.of("data", response(node)));
    }
    @ExceptionHandler(NodeService.Problem.class)
    public ResponseEntity<?> handle(NodeService.Problem p) {
        return ResponseEntity.status(p.status).cacheControl(CacheControl.noStore()).body(Map.of("error",Map.of("code",p.code,"message",p.getMessage())));
    }
}

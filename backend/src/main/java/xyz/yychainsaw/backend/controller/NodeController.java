package xyz.yychainsaw.backend.controller;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.backend.model.NdNode;
import xyz.yychainsaw.backend.security.SessionUser;
import xyz.yychainsaw.backend.service.NodeService;
import xyz.yychainsaw.backend.service.TrashService;

@RestController @RequestMapping("/api/v1")
public class NodeController {
    private final NodeService service;
    private final TrashService trash;
    public NodeController(NodeService service, TrashService trash) { this.service = service; this.trash=trash; }
    public record FolderRequest(String parentId, String name) {}
    public record RenameRequest(String name) {}
    public record NodeResponse(String id, String parentId, String name, String type, String sizeBytes, String version, String createdAt, String updatedAt) {}
    private NodeResponse response(NdNode node) {
        return new NodeResponse(node.getId().toString(), node.getParentId().toString(), node.getName(), node.getNodeType(),
            node.getSizeBytes() == null ? null : node.getSizeBytes().toString(), node.getVersion().toString(),
            node.getCreatedAt() == null ? null : node.getCreatedAt().atOffset(ZoneOffset.UTC).toString(),
            node.getUpdatedAt() == null ? null : node.getUpdatedAt().atOffset(ZoneOffset.UTC).toString());
    }
    private long user(Authentication auth) { return NodeService.id(((SessionUser) auth.getPrincipal()).id(), false); }
    @GetMapping("/nodes")
    public ResponseEntity<?> list(Authentication auth, @RequestParam String parentId,
        @RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "50") int pageSize, @RequestParam(defaultValue = "name") String sort) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new NodeService.Problem(400, "INVALID_ARGUMENT", "分页参数无效");
        long offset = (long) (page - 1) * pageSize;
        List<NdNode> rows = service.list(user(auth), NodeService.id(parentId, true), keyword, pageSize, offset, sort);
        boolean hasMore = rows.size() > pageSize;
        List<NodeResponse> items = rows.subList(0, Math.min(rows.size(), pageSize)).stream().map(this::response).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data", Map.of("items",items,"page",page,"pageSize",pageSize,"hasMore",hasMore)));
    }
    @PostMapping("/folders")
    public ResponseEntity<?> create(Authentication auth, @RequestBody FolderRequest body) {
        NdNode node = service.create(user(auth), NodeService.id(body.parentId(), true), body.name());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).eTag("\"v" + node.getVersion() + "\"").body(Map.of("data", response(node)));
    }
    @PatchMapping("/nodes/{id}/name")
    public ResponseEntity<?> rename(Authentication auth, @PathVariable String id,
            @RequestHeader(value="If-Match",required=false) String ifMatch, @RequestBody RenameRequest body) {
        NdNode node=service.rename(user(auth),NodeService.id(id,false),NodeService.expectedVersion(ifMatch),body.name());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"v"+node.getVersion()+"\"").body(Map.of("data",response(node)));
    }
    @DeleteMapping("/nodes/{id}")
    public ResponseEntity<?> delete(Authentication auth, @PathVariable String id,
            @RequestHeader(value="If-Match",required=false) String ifMatch) {
        trash.trash(user(auth),NodeService.id(id,false),NodeService.expectedVersion(ifMatch));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("data",Map.of("id",id,"state","TRASHED")));
    }
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<?> concurrent(ConcurrencyFailureException ex) {
        return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(Map.of("error",Map.of("code","CONCURRENT_CHANGE","message","操作与其他请求冲突，请刷新后重试")));
    }
    @ExceptionHandler(NodeService.Problem.class)
    public ResponseEntity<?> handle(NodeService.Problem p) {
        return ResponseEntity.status(p.status).cacheControl(CacheControl.noStore()).body(Map.of("error",Map.of("code",p.code,"message",p.getMessage())));
    }
}

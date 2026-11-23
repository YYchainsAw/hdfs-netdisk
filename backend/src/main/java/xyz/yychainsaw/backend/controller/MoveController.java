package xyz.yychainsaw.backend.controller;

import java.util.List;
import java.util.Map;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import xyz.yychainsaw.backend.security.SessionUser;
import xyz.yychainsaw.backend.service.NodeBatchService;
import xyz.yychainsaw.backend.service.NodeService;

@RestController
@RequestMapping("/api/v1")
public class MoveController {
    private final NodeBatchService service;
    public MoveController(NodeBatchService service) { this.service=service; }
    public record MoveRequest(String targetParentId,List<NodeBatchService.Entry> items) {}
    @PostMapping("/nodes/move")
    public ResponseEntity<?> move(Authentication auth,@RequestBody MoveRequest request) {
        long user=NodeService.id(((SessionUser)auth.getPrincipal()).id(),false);
        service.move(user,request.targetParentId(),request.items());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data",Map.of("moved",request.items().size())));
    }
    @ExceptionHandler(NodeService.Problem.class)
    public ResponseEntity<?> problem(NodeService.Problem p) {
        return ResponseEntity.status(p.status).cacheControl(CacheControl.noStore()).body(Map.of("error",Map.of("code",p.code,"message",p.getMessage())));
    }
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<?> concurrent(ConcurrencyFailureException ignored) {
        return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(Map.of("error",Map.of("code","CONCURRENT_CHANGE","message","目录正在变动，请刷新后重试")));
    }
}

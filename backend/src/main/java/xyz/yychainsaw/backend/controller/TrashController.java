package xyz.yychainsaw.backend.controller;

import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import xyz.yychainsaw.backend.security.SessionUser;
import xyz.yychainsaw.backend.service.NodeService;
import xyz.yychainsaw.backend.service.TrashService;

@RestController @RequestMapping("/api/v1/trash")
public class TrashController {
    private final TrashService service;
    public TrashController(TrashService service) { this.service=service; }
    private long user(Authentication auth) { return NodeService.id(((SessionUser)auth.getPrincipal()).id(),false); }
    @GetMapping
    public ResponseEntity<?> list(Authentication auth,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize) {
        if(page<1 || pageSize<1 || pageSize>100) throw new NodeService.Problem(400,"INVALID_ARGUMENT","分页参数无效");
        List<TrashService.TrashItem> all=service.list(user(auth),pageSize,(long)(page-1)*pageSize);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data",Map.of("items",all.subList(0,Math.min(all.size(),pageSize)),"page",page,"pageSize",pageSize,"hasMore",all.size()>pageSize)));
    }
    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restore(Authentication auth,@PathVariable String id,@RequestHeader(value="If-Match",required=false) String match) {
        var item=service.restore(user(auth),NodeService.id(id,false),NodeService.expectedVersion(match));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data",item));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<?> purge(Authentication auth,@PathVariable String id,@RequestHeader(value="If-Match",required=false) String match) {
        service.purge(user(auth),NodeService.id(id,false),NodeService.expectedVersion(match));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("data",Map.of("id",id,"state","DELETING")));
    }
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<?> concurrent(ConcurrencyFailureException ex) {
        return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(Map.of("error",Map.of("code","CONCURRENT_CHANGE","message","操作与其他请求冲突，请刷新后重试")));
    }
    @ExceptionHandler(NodeService.Problem.class)
    public ResponseEntity<?> handle(NodeService.Problem p) { return ResponseEntity.status(p.status).cacheControl(CacheControl.noStore()).body(Map.of("error",Map.of("code",p.code,"message",p.getMessage()))); }
}

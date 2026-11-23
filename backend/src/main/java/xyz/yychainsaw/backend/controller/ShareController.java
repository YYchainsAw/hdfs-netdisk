package xyz.yychainsaw.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import xyz.yychainsaw.backend.model.NdNode;
import xyz.yychainsaw.backend.repository.DownloadMapper;
import xyz.yychainsaw.backend.security.SessionUser;
import xyz.yychainsaw.backend.service.NodeService;
import xyz.yychainsaw.backend.service.StorageOperations;
import xyz.yychainsaw.backend.storage.HdfsStorage;

/** Share links are random bearer tokens; passwords never appear in URLs or logs. */
@RestController
@RequestMapping("/api/v1")
public class ShareController {
    private final JdbcTemplate db;
    private final DownloadMapper files;
    private final HdfsStorage hdfs;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, long[]> attempts = new HashMap<>();
    private static final String VERIFIED = ShareController.class.getName()+".verified";
    private static final String SELECT_VALID = "SELECT s.id,s.owner_id,s.node_id,s.code_hash,s.expires_at,s.access_count,n.name,n.size_bytes,n.storage_key " +
        "FROM nd_share s JOIN nd_node n ON n.id=s.node_id AND n.user_id=s.owner_id " +
        "WHERE s.token_hash=? AND s.revoked=FALSE AND s.expires_at>CURRENT_TIMESTAMP " +
        "AND n.status='READY' AND n.node_type='FILE' AND n.version=s.node_version";
    public ShareController(JdbcTemplate db, DownloadMapper files, HdfsStorage hdfs, PasswordEncoder passwords) {
        this.db=db; this.files=files; this.hdfs=hdfs; this.passwords=passwords;
    }
    public record CreateShare(String nodeId, Integer expiresHours, Integer maxVisits) {}
    public record CodeRequest(String code) {}
    private NodeService.Problem error(int status,String code,String message) { return new NodeService.Problem(status,code,message); }
    private long user(Authentication a) { return NodeService.id(((SessionUser)a.getPrincipal()).id(),false); }
    private static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private Map<String,Object> valid(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw error(404,"SHARE_UNAVAILABLE","分享链接不存在或已失效");
        var rows=db.queryForList(SELECT_VALID,digest(token));
        if(rows.isEmpty()) throw error(404,"SHARE_UNAVAILABLE","分享链接不存在或已失效");
        return rows.get(0);
    }
    private synchronized boolean allowAttempt(String ip, String hash) {
        long now=System.currentTimeMillis(); attempts.entrySet().removeIf(e->e.getValue()[0] <= now);
        String key=ip+":"+hash;
        long[] entry=attempts.get(key);
        if(entry==null) { if(attempts.size()>=10000) return false; attempts.put(key,new long[]{now+600000,1}); return true; }
        if(entry[1]>=10) return false; entry[1]++; return true;
    }
    @PostMapping("/shares")
    public ResponseEntity<?> create(Authentication auth,@RequestBody CreateShare req) {
        long owner=user(auth), node=NodeService.id(req.nodeId(),false);
        if(req.expiresHours()==null || req.expiresHours()<1 || req.expiresHours()>720)
            throw error(400,"INVALID_EXPIRY","有效期须为 1 至 720 小时");
        if(req.maxVisits()!=null && (req.maxVisits()<1 || req.maxVisits()>10000))
            throw error(400,"INVALID_LIMIT","访问次数限制须为 1 至 10000");
        NdNode file=files.findVisibleFile(owner,node);
        if(file==null) throw error(404,"NOT_FOUND","文件不存在或不可访问");
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String code=String.format(java.util.Locale.ROOT,"%06d",random.nextInt(1000000));
        String hash=digest(token), encoded=passwords.encode(code);
        LocalDateTime expiry=LocalDateTime.now(ZoneOffset.UTC).plusHours(req.expiresHours());
        GeneratedKeyHolder holder=new GeneratedKeyHolder();
        db.update(connection->{
            PreparedStatement p=connection.prepareStatement("INSERT INTO nd_share(owner_id,node_id,node_version,token_hash,code_hash,expires_at,max_visits) VALUES (?,?,?,?,?,?,?)",new String[]{"id"});
            p.setLong(1,owner);p.setLong(2,node);p.setLong(3,file.getVersion());p.setString(4,hash);p.setString(5,encoded);p.setObject(6,expiry);p.setObject(7,req.maxVisits());return p;
        },holder);
        if(holder.getKey()==null) throw new IllegalStateException("Share ID not returned");
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(Map.of("data",Map.of(
            "id",holder.getKey().toString(),"token",token,"code",code,"expiresAt",expiry.atOffset(ZoneOffset.UTC).toString())));
    }
    @GetMapping("/shares")
    public ResponseEntity<?> list(Authentication auth,@RequestParam String nodeId) {
        long owner=user(auth), node=NodeService.id(nodeId,false);
        if(files.findVisibleFile(owner,node)==null) throw error(404,"NOT_FOUND","文件不存在或不可访问");
        var shares=db.queryForList("SELECT s.id,s.expires_at,s.access_count,s.created_at FROM nd_share s JOIN nd_node n ON n.id=s.node_id AND n.user_id=s.owner_id AND n.status='READY' AND n.version=s.node_version WHERE s.owner_id=? AND s.node_id=? AND s.revoked=FALSE AND s.expires_at>CURRENT_TIMESTAMP ORDER BY s.id DESC LIMIT 100",owner,node).stream()
            .map(row->Map.of("id",row.get("id").toString(),"expiresAt",row.get("expires_at").toString(),"accessCount",row.get("access_count").toString())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data",shares));
    }
    @DeleteMapping("/shares/{id}")
    public ResponseEntity<?> revoke(Authentication auth,@PathVariable String id) {
        long owner=user(auth), share=NodeService.id(id,false);
        if(db.update("UPDATE nd_share SET revoked=TRUE WHERE id=? AND owner_id=?",share,owner)==0)
            throw error(404,"NOT_FOUND","分享不存在或不可访问");
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/public/shares/{token}")
    public ResponseEntity<?> metadata(@PathVariable String token) {
        var row=valid(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data",Map.of("name",row.get("name"),"sizeBytes",row.get("size_bytes").toString())));
    }
    @PostMapping("/public/shares/{token}/verify")
    public ResponseEntity<?> verify(@PathVariable String token,@RequestBody CodeRequest req,HttpServletRequest request) {
        var row=valid(token); String key=digest(token);
        if(!allowAttempt(request.getRemoteAddr(),key)) throw error(429,"RATE_LIMITED","尝试次数过多，请稍后重试");
        if(req.code()==null || !req.code().matches("[0-9]{6}") || !passwords.matches(req.code(),row.get("code_hash").toString()))
            throw error(400,"INVALID_CODE","提取码错误");
        HttpSession session=request.getSession(true);
        synchronized(session) {
        @SuppressWarnings("unchecked") Set<String> prior=(Set<String>)session.getAttribute(VERIFIED);
        Set<String> granted=prior==null?new HashSet<>():new HashSet<>(prior);
        if(!granted.contains(key)) {
            int used=db.update("UPDATE nd_share SET access_count=access_count+1 WHERE id=? AND revoked=FALSE AND expires_at>CURRENT_TIMESTAMP AND (max_visits IS NULL OR access_count<max_visits) AND EXISTS (SELECT 1 FROM nd_node n WHERE n.id=nd_share.node_id AND n.user_id=nd_share.owner_id AND n.status='READY' AND n.node_type='FILE' AND n.version=nd_share.node_version)",row.get("id"));
            if(used!=1) throw error(410,"SHARE_LIMIT","分享已失效或访问次数已用完");
        }
        if(granted.size()>=64) granted.clear();
        granted.add(key); session.setAttribute(VERIFIED,granted);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data",Map.of("verified",true)));
        }
    }
    @GetMapping("/public/shares/{token}/content")
    public void content(@PathVariable String token,HttpServletRequest request,HttpServletResponse response) throws IOException {
        var guard=StorageOperations.LOCK.readLock(); guard.lock();
        try {
            var row=valid(token); HttpSession session=request.getSession(false);
            Object grants=session==null?null:session.getAttribute(VERIFIED);
            if(!(grants instanceof Set<?> keys) || !keys.contains(digest(token))) throw error(403,"CODE_REQUIRED","请输入提取码");
            InputStream in;
            try { in=hdfs.open(row.get("storage_key").toString()); }
            catch(IOException e) { throw error(503,"STORAGE_UNAVAILABLE","存储服务暂不可用"); }
            try(in) {
                response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(row.get("name").toString(),StandardCharsets.UTF_8).build().toString());
                response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");response.setHeader("X-Content-Type-Options","nosniff");
                response.setContentLengthLong(((Number)row.get("size_bytes")).longValue());in.transferTo(response.getOutputStream());
            }
        } finally { guard.unlock(); }
    }
    @ExceptionHandler(NodeService.Problem.class)
    public ResponseEntity<?> handle(NodeService.Problem p) {
        return ResponseEntity.status(p.status).cacheControl(CacheControl.noStore()).body(Map.of("error",Map.of("code",p.code,"message",p.getMessage())));
    }
}

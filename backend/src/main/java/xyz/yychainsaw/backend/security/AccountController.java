package xyz.yychainsaw.backend.security;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import xyz.yychainsaw.backend.model.NdUser;
import xyz.yychainsaw.backend.repository.NdNodeMapper;
import xyz.yychainsaw.backend.repository.NdUserMapper;
import xyz.yychainsaw.backend.service.NodeService;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public class AccountController {
    private final NdUserMapper users;
    private final NdNodeMapper nodes;
    private final PasswordEncoder passwords;
    private final AccountRateLimiter limiter;

    AccountController(NdUserMapper users, NdNodeMapper nodes, PasswordEncoder passwords, AccountRateLimiter limiter) {
        this.users=users; this.nodes=nodes; this.passwords=passwords; this.limiter=limiter;
    }
    public record RegisterRequest(String username, String password) {}
    public record PasswordRequest(String oldPassword, String newPassword) {}

    private static NodeService.Problem fail(int status, String code, String msg) { return new NodeService.Problem(status, code, msg); }
    private static void validPassword(String value) {
        if (value == null || value.codePointCount(0,value.length()) < 12 || value.getBytes(StandardCharsets.UTF_8).length > 72)
            throw fail(400,"INVALID_PASSWORD","密码至少 12 个字符，UTF-8 不超过 72 字节");
    }
    private static long user(Authentication auth) { return NodeService.id(((SessionUser) auth.getPrincipal()).id(),false); }

    @PostMapping("/auth/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req, HttpServletRequest request) {
        if (!limiter.allow("r:" + request.getRemoteAddr(),5)) throw fail(429,"RATE_LIMITED","注册请求过于频繁，请稍后重试");
        String username=req.username();
        if (username == null || !username.matches("[A-Za-z0-9._-]{3,64}"))
            throw fail(400,"INVALID_USERNAME","用户名须为 3 至 64 位字母、数字、点、下划线或连字符");
        validPassword(req.password());
        NdUser user = new NdUser(); user.setUsername(username); user.setPasswordHash(passwords.encode(req.password())); user.setStatus("ACTIVE");
        try { users.insert(user); }
        catch (DuplicateKeyException e) { throw fail(409,"USERNAME_TAKEN","该用户名已被使用"); }
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(Map.of("data",Map.of("username",username)));
    }

    @PostMapping("/auth/password")
    public ResponseEntity<?> password(Authentication auth, @RequestBody PasswordRequest req, HttpServletRequest request) {
        long id=user(auth);
        if (!limiter.allow("p:" + request.getRemoteAddr() + ":" + id,6))
            throw fail(429,"RATE_LIMITED","密码验证过于频繁，请稍后重试");
        validPassword(req.newPassword());
        String old=req.oldPassword();
        if (old == null || old.getBytes(StandardCharsets.UTF_8).length > 72)
            throw fail(400,"PASSWORD_INCORRECT","原密码不正确");
        NdUser current=users.selectById(id);
        if (current == null || !"ACTIVE".equals(current.getStatus()) || !passwords.matches(old,current.getPasswordHash()))
            throw fail(400,"PASSWORD_INCORRECT","原密码不正确");
        String next=passwords.encode(req.newPassword());
        if (users.replacePassword(id,current.getPasswordHash(),next) != 1)
            throw fail(409,"PASSWORD_CHANGED","账号密码已发生变化，请重新登录");
        HttpSession session=request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/usage")
    public ResponseEntity<?> usage(Authentication auth) {
        long id=user(auth);
        var used=nodes.sumRetainedFileBytes(id);
        long count=nodes.countRetainedFiles(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data",Map.of(
            "usedBytes",used.toBigIntegerExact().toString(),"fileCount",Long.toString(count))));
    }

    @ExceptionHandler(NodeService.Problem.class)
    public ResponseEntity<?> problem(NodeService.Problem p) {
        return ResponseEntity.status(p.status).cacheControl(CacheControl.noStore())
            .body(Map.of("error",Map.of("code",p.code,"message",p.getMessage())));
    }
}

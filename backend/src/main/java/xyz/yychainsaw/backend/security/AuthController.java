package xyz.yychainsaw.backend.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.backend.model.NdUser;
import xyz.yychainsaw.backend.repository.NdUserMapper;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final NdUserMapper users;
    private final PasswordEncoder passwords;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final CsrfTokenRepository tokens;
    private final LoginRateLimiter limiter;
    private final String dummyHash;

    AuthController(NdUserMapper users, PasswordEncoder passwords, SecurityContextRepository contexts,
                   SessionAuthenticationStrategy sessions, CsrfTokenRepository tokens, LoginRateLimiter limiter) {
        this.users = users; this.passwords = passwords; this.contexts = contexts; this.sessions = sessions;
        this.tokens = tokens; this.limiter = limiter;
        this.dummyHash = passwords.encode("dummy-password-not-a-real-account");
    }

    public record LoginRequest(String username, String password) {}
    private ResponseEntity<?> ok(Object data) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("data", data)); }
    private ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", Map.of("code",code,"message",message)));
    }

    @GetMapping("/csrf")
    public ResponseEntity<?> csrf(CsrfToken token) { return ok(Map.of("csrfToken", token.getToken())); }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String client = request.getRemoteAddr();
        if (!limiter.allowClient(client)) return error(429,"RATE_LIMITED","请求过于频繁，请稍后重试");
        String username = body.username(); String raw = body.password();
        if (username == null || username.isBlank() || username.length() > 64 || raw == null
                || raw.getBytes(StandardCharsets.UTF_8).length > 72) return error(401,"INVALID_CREDENTIALS","账号或密码错误");
        NdUser user = users.selectOne(Wrappers.<NdUser>lambdaQuery().eq(NdUser::getUsername, username));
        String accountKey = user == null ? "unknown" : "id:" + user.getId();
        if (!limiter.allowAccount(client, accountKey)) return error(429,"RATE_LIMITED","请求过于频繁，请稍后重试");
        String hash = user == null ? dummyHash : user.getPasswordHash();
        boolean matches = false;
        try { matches = passwords.matches(raw, hash); } catch (IllegalArgumentException ignored) { /* uniform rejection */ }
        if (user == null || !matches || !"ACTIVE".equals(user.getStatus()))
            return error(401,"INVALID_CREDENTIALS","账号或密码错误");

        SessionUser principal = new SessionUser(String.valueOf(user.getId()), user.getUsername(), CredentialStamp.of(user.getPasswordHash()));
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        sessions.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        tokens.saveToken(null, request, response);
        CsrfToken rawToken = tokens.generateToken(request);
        tokens.saveToken(rawToken, request, response);
        new XorCsrfTokenRequestAttributeHandler().handle(request, response, () -> rawToken);
        CsrfToken masked = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        limiter.success(client, accountKey);
        return ok(Map.of("user", Map.of("id", principal.id(), "username", principal.username()), "csrfToken", masked.getToken()));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionUser user))
            return error(401,"UNAUTHENTICATED","请先登录");
        return ok(Map.of("id", user.id(), "username", user.username()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}

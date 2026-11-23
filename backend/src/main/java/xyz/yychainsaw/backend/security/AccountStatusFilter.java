package xyz.yychainsaw.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import xyz.yychainsaw.backend.model.NdUser;
import xyz.yychainsaw.backend.repository.NdUserMapper;

/** Re-check accounts on authenticated requests, including after administrative disablement. */
final class AccountStatusFilter extends OncePerRequestFilter {
    private final NdUserMapper users;
    AccountStatusFilter(NdUserMapper users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SessionUser principal) {
            NdUser current = users.selectById(principal.id());
            if (current == null || !"ACTIVE".equals(current.getStatus()) || !current.getUsername().equals(principal.username())
                    || !CredentialStamp.of(current.getPasswordHash()).equals(principal.credentialStamp())) {
                HttpSession session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录\"}}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

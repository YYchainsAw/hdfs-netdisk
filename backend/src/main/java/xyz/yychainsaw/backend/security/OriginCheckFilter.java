package xyz.yychainsaw.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.web.filter.OncePerRequestFilter;

/** Additional protection for browser state changes; CSRF tokens remain mandatory. */
final class OriginCheckFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        String origin = request.getHeader("Origin");
        if (!method.equals("GET") && !method.equals("HEAD") && !method.equals("OPTIONS") && origin != null) {
            try {
                URI uri = URI.create(origin);
                int originPort = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
                if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                        || uri.getRawFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())
                        || !request.getScheme().equalsIgnoreCase(uri.getScheme())
                        || !request.getServerName().equalsIgnoreCase(uri.getHost())
                        || request.getServerPort() != originPort) {
                    reject(response); return;
                }
            } catch (IllegalArgumentException ex) { reject(response); return; }
        }
        chain.doFilter(request, response);
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":{\"code\":\"CSRF_INVALID\",\"message\":\"请求来源无效\"}}");
    }
}

package xyz.yychainsaw.backend.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import xyz.yychainsaw.backend.repository.NdUserMapper;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() { return new ChangeSessionIdAuthenticationStrategy(); }
    @Bean
    CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SecurityContextRepository contexts, CsrfTokenRepository tokens, NdUserMapper users) throws Exception {
        http.securityContext(c -> c.securityContextRepository(contexts).requireExplicitSave(true))
            .csrf(c -> c.csrfTokenRepository(tokens))
            .addFilterBefore(new OriginCheckFilter(), CsrfFilter.class)
            .addFilterBefore(new AccountStatusFilter(users), AuthorizationFilter.class)
            .authorizeHttpRequests(a -> a.requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                    .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录\"}}");
            }).accessDeniedHandler((req, res, ex) -> {
                res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"error\":{\"code\":\"CSRF_INVALID\",\"message\":\"安全验证失败，请刷新后重试\"}}");
            }))
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable());
        return http.build();
    }
}

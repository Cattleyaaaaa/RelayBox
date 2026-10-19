package dev.relaybox.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import dev.relaybox.service.TenantEventRateLimiter;
import dev.relaybox.service.TenantBurstRateLimiter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    private final byte[] masterKey;
    private final JdbcTemplate jdbc;
    private final TenantEventRateLimiter rateLimiter;
    private final TenantBurstRateLimiter burstRateLimiter;

    public ApiKeyFilter(@Value("${relaybox.master-key}") String masterKey, JdbcTemplate jdbc,
                        TenantEventRateLimiter rateLimiter, TenantBurstRateLimiter burstRateLimiter) {
        this.masterKey = masterKey.getBytes(StandardCharsets.UTF_8);
        this.jdbc = jdbc;
        this.rateLimiter = rateLimiter;
        this.burstRateLimiter = burstRateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader("X-API-Key");
        boolean tenantCreation = "POST".equalsIgnoreCase(request.getMethod())
                && "/api/v1/tenants".equals(request.getRequestURI());
        if (provided == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid API key");
            return;
        }
        if (tenantCreation) {
            if (MessageDigest.isEqual(masterKey, provided.getBytes(StandardCharsets.UTF_8))) {
                chain.doFilter(request, response);
            } else {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Master API key required");
            }
            return;
        }
        String apiKeyHash = sha256(provided);
        ListResult tenant = findTenant(apiKeyHash);
        if (tenant == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid API key");
            return;
        }
        boolean keyRotation = "POST".equalsIgnoreCase(request.getMethod())
                && "/api/v1/tenants/api-key/rotate".equals(request.getRequestURI());
        if (keyRotation && !tenant.currentKey()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "API key rotation requires the current API key");
            return;
        }
        request.setAttribute("tenantId", tenant.id());
        request.setAttribute("apiKeyHash", apiKeyHash);
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/api/v1/events".equals(request.getRequestURI())) {
            TenantBurstRateLimiter.Decision burstDecision = burstRateLimiter.acquire(
                    tenant.id(), tenant.eventRateLimitPerMinute());
            if (burstDecision.enabled()) {
                if (burstDecision.remaining() >= 0) {
                    response.setHeader("X-RateLimit-Burst-Remaining", Integer.toString(burstDecision.remaining()));
                }
                if (!burstDecision.allowed()) {
                    response.setHeader("Retry-After", Integer.toString(burstDecision.retryAfterSeconds()));
                    response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), "tenant burst rate limit exceeded");
                    return;
                }
            }
            TenantEventRateLimiter.Decision decision = rateLimiter.acquire(tenant.id(), tenant.eventRateLimitPerMinute());
            response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
            response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
            if (!decision.allowed()) {
                response.setHeader("Retry-After", Integer.toString(decision.retryAfterSeconds()));
                response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), "tenant event rate limit exceeded");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private ListResult findTenant(String apiKeyHash) {
        return jdbc.query("""
                SELECT id, event_rate_limit_per_minute, api_key_hash = ? AS current_key
                FROM tenant
                WHERE enabled = TRUE AND (
                    api_key_hash = ? OR (previous_api_key_hash = ? AND previous_api_key_expires_at > now())
                )
                """, rs -> rs.next() ? new ListResult(rs.getObject("id", UUID.class),
                rs.getInt("event_rate_limit_per_minute"), rs.getBoolean("current_key")) : null,
                apiKeyHash, apiKeyHash, apiKeyHash);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ListResult(UUID id, int eventRateLimitPerMinute, boolean currentKey) {}
}

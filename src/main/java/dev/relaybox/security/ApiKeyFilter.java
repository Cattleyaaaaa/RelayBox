package dev.relaybox.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
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

    public ApiKeyFilter(@Value("${relaybox.master-key}") String masterKey, JdbcTemplate jdbc) {
        this.masterKey = masterKey.getBytes(StandardCharsets.UTF_8);
        this.jdbc = jdbc;
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
        request.setAttribute("tenantId", tenant.id());
        chain.doFilter(request, response);
    }

    private ListResult findTenant(String apiKeyHash) {
        return jdbc.query("SELECT id FROM tenant WHERE api_key_hash = ? AND enabled = TRUE", rs ->
                rs.next() ? new ListResult(rs.getObject("id", UUID.class)) : null, apiKeyHash);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ListResult(UUID id) {}
}

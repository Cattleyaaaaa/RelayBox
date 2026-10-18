package dev.relaybox.service;

import dev.relaybox.api.ApiModels;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TenantService {
    private final JdbcTemplate jdbc;
    private final int defaultEventRateLimitPerMinute;
    private final SecureRandom random = new SecureRandom();

    public TenantService(JdbcTemplate jdbc,
                         @Value("${relaybox.rate-limit.default-events-per-minute:120}") int defaultEventRateLimitPerMinute) {
        this.jdbc = jdbc;
        this.defaultEventRateLimitPerMinute = Math.max(1, Math.min(10000, defaultEventRateLimitPerMinute));
    }

    @Transactional
    public ApiModels.TenantResponse create(ApiModels.CreateTenantRequest request) {
        String apiKey = createApiKey();
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now();
        int eventRateLimit = request.eventRateLimitPerMinute() == null
                ? defaultEventRateLimitPerMinute : request.eventRateLimitPerMinute();
        try {
            jdbc.update("INSERT INTO tenant (id, name, api_key_hash, event_rate_limit_per_minute) VALUES (?, ?, ?, ?)",
                    id, request.name().trim(), sha256(apiKey), eventRateLimit);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new IllegalArgumentException("tenant name is already in use");
        }
        return new ApiModels.TenantResponse(id, request.name().trim(), apiKey, eventRateLimit, createdAt);
    }

    @Transactional
    public ApiModels.RotateTenantApiKeyResponse rotateApiKey(UUID tenantId, String currentApiKeyHash,
                                                              Integer requestedGracePeriodSeconds) {
        int gracePeriodSeconds = requestedGracePeriodSeconds == null ? 300 : requestedGracePeriodSeconds;
        if (gracePeriodSeconds < 0 || gracePeriodSeconds > 86400) {
            throw new IllegalArgumentException("gracePeriodSeconds must be between 0 and 86400");
        }

        String apiKey = createApiKey();
        java.util.List<Instant> expirations = jdbc.query("""
                UPDATE tenant
                SET previous_api_key_hash = CASE WHEN ? > 0 THEN api_key_hash ELSE NULL END,
                    previous_api_key_expires_at = CASE
                        WHEN ? > 0 THEN now() + (? * interval '1 second')
                        ELSE NULL
                    END,
                    api_key_hash = ?
                WHERE id = ? AND api_key_hash = ? AND enabled = TRUE
                RETURNING previous_api_key_expires_at
                """, (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(),
                gracePeriodSeconds, gracePeriodSeconds, gracePeriodSeconds, sha256(apiKey), tenantId, currentApiKeyHash);
        if (expirations.isEmpty()) throw new IllegalArgumentException("API key changed before rotation completed; retry with the current key");
        return new ApiModels.RotateTenantApiKeyResponse(apiKey, expirations.get(0));
    }

    private String createApiKey() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return "rbx_" + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

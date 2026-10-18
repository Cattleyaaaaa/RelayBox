package dev.relaybox.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Local-only webhook receiver used to demonstrate retries and manual replay. */
@RestController
@RequestMapping("/dev/receiver")
@ConditionalOnProperty(name = "relaybox.test-receiver.enabled", havingValue = "true")
public class DevelopmentReceiverController {
    private static final int MAX_RECORDED_REQUESTS = 100;
    private final byte[] masterKey;
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private final AtomicInteger failureStatus = new AtomicInteger(500);
    private final AtomicLong totalRequests = new AtomicLong();
    private final Deque<ReceivedRequest> recentRequests = new ArrayDeque<>();

    public DevelopmentReceiverController(@Value("${relaybox.master-key}") String masterKey) {
        this.masterKey = masterKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/deliver")
    public ResponseEntity<Void> receive(HttpServletRequest request, @RequestBody String body) {
        long requestNumber = totalRequests.incrementAndGet();
        ReceivedRequest received = new ReceivedRequest(
                requestNumber,
                Instant.now(),
                request.getHeader("X-Webhook-Id"),
                request.getHeader("X-Webhook-Delivery"),
                request.getHeader("X-Webhook-Timestamp"),
                request.getHeader("X-Webhook-Signature"),
                body);
        synchronized (recentRequests) {
            recentRequests.addFirst(received);
            while (recentRequests.size() > MAX_RECORDED_REQUESTS) recentRequests.removeLast();
        }

        int remaining = failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1));
        return ResponseEntity.status(remaining > 0 ? failureStatus.get() : HttpStatus.OK.value()).build();
    }

    @PostMapping("/config")
    public ReceiverState configure(@RequestHeader(name = "X-API-Key", required = false) String providedKey,
                                   @Valid @RequestBody ReceiverConfig config) {
        requireMasterKey(providedKey);
        failuresRemaining.set(config.failuresBeforeSuccess());
        failureStatus.set(config.failureStatus());
        return state();
    }

    @PostMapping("/reset")
    public ReceiverState reset(@RequestHeader(name = "X-API-Key", required = false) String providedKey) {
        requireMasterKey(providedKey);
        failuresRemaining.set(0);
        failureStatus.set(HttpStatus.INTERNAL_SERVER_ERROR.value());
        totalRequests.set(0);
        synchronized (recentRequests) {
            recentRequests.clear();
        }
        return state();
    }

    @GetMapping("/state")
    public ReceiverState getState(@RequestHeader(name = "X-API-Key", required = false) String providedKey) {
        requireMasterKey(providedKey);
        return state();
    }

    private ReceiverState state() {
        List<ReceivedRequest> requests;
        synchronized (recentRequests) {
            requests = new ArrayList<>(recentRequests);
        }
        return new ReceiverState(totalRequests.get(), failuresRemaining.get(), failureStatus.get(), requests);
    }

    private void requireMasterKey(String providedKey) {
        if (providedKey == null || !MessageDigest.isEqual(masterKey, providedKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Master API key required");
        }
    }

    public record ReceiverConfig(@Min(0) @Max(1000) int failuresBeforeSuccess,
                                 @Min(400) @Max(599) int failureStatus) {}

    public record ReceiverState(long totalRequests, int failuresRemaining, int failureStatus,
                                List<ReceivedRequest> recentRequests) {}

    public record ReceivedRequest(long requestNumber, Instant receivedAt, String eventId,
                                  String deliveryId, String timestamp, String signature, String body) {}
}

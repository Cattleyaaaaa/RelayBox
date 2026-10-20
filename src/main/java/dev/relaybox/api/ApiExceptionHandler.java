package dev.relaybox.api;

import dev.relaybox.service.WebhookService.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ObjectMapper mapper;

    public ApiExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException exception, HttpServletRequest request) {
        log.warn("Invalid API request: method={} path={} error={}",
                request.getMethod(), request.getRequestURI(), exception.toString());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest(MethodArgumentNotValidException exception, HttpServletRequest request) {
        log.warn("Request validation failed: method={} path={} fields={}",
                request.getMethod(), request.getRequestURI(),
                exception.getBindingResult().getFieldErrors().stream().map(error -> error.getField()).toList());
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst().orElse("request validation failed");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler({ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail invalidBinding(Exception exception, HttpServletRequest request) {
        log.warn("Request binding failed: method={} path={} error={}",
                request.getMethod(), request.getRequestURI(), exception.toString());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Invalid request path, parameter, or required attribute");
    }

    @ExceptionHandler(RestClientResponseException.class)
    ProblemDetail downstreamRejected(RestClientResponseException exception, HttpServletRequest request) {
        int code = exception.getStatusCode().value();
        HttpStatus resolved = HttpStatus.resolve(code);
        HttpStatus status = resolved != null && resolved.is4xxClientError() ? resolved : HttpStatus.BAD_GATEWAY;
        String detail = downstreamDetail(exception);
        log.warn("Delivery service rejected request: method={} path={} status={} detail={}",
                request.getMethod(), request.getRequestURI(), code, detail);
        return ProblemDetail.forStatusAndDetail(status, detail);
    }

    @ExceptionHandler(ResourceAccessException.class)
    ProblemDetail deliveryUnavailable(ResourceAccessException exception, HttpServletRequest request,
                                      HttpServletResponse response) {
        log.warn("Delivery service unavailable: method={} path={} cause={}", request.getMethod(),
                request.getRequestURI(), exception.getMostSpecificCause().getClass().getSimpleName());
        response.setHeader("Retry-After", "2");
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Delivery service is temporarily unavailable; retry later");
    }

    private String downstreamDetail(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        if (body != null && !body.isBlank()) {
            try {
                JsonNode node = mapper.readTree(body);
                if (node.hasNonNull("detail")) return node.get("detail").asText();
                if (node.hasNonNull("message")) return node.get("message").asText();
            } catch (JacksonException ignored) {
                // Fall through to the status-based message.
            }
        }
        return "delivery service returned HTTP " + exception.getStatusCode().value();
    }
}

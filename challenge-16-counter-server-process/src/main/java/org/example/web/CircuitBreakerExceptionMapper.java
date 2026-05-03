package org.example.web;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Translates Resilience4j's CallNotPermittedException — thrown when a
 * CircuitBreaker is OPEN — into a 503 with Retry-After. Fast-fail signal
 * to the client: "this dependency is unavailable; back off for ~10s and
 * retry."
 */
@Provider
public class CircuitBreakerExceptionMapper implements ExceptionMapper<CallNotPermittedException> {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerExceptionMapper.class);

    @Override
    public Response toResponse(CallNotPermittedException e) {
        String breakerName = e.getCausingCircuitBreakerName();
        log.info("breaker.open.503 breaker={}", breakerName);
        return Response.status(503)
                .header("Retry-After", "10")
                .entity(Map.of(
                        "error", "service_temporarily_unavailable",
                        "breaker", breakerName))
                .build();
    }
}

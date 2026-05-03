package org.example.web;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Jersey filter that tags every HTTP request with a unique ID and makes it
 * available to every log line issued while handling that request.
 *
 * Mechanics:
 *   - On request: generate a random UUID, stash it in SLF4J's MDC (Mapped
 *     Diagnostic Context) under key "requestId", and echo it back in the
 *     "X-Request-Id" response header so the client can reference it in
 *     bug reports.
 *   - On response: clear the MDC so the request ID doesn't leak to other
 *     threads later reusing the same thread-pool thread.
 *
 * Why this matters: every log line we emit while handling a request
 * automatically carries the requestId. Later, if something fails, we can
 * grep the log for that one request ID and see the full sequence of events.
 * That's the lightweight "tracing" for a single-service app.
 */
@Provider
public class RequestIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    public void filter(ContainerRequestContext request) {
        // Honor an incoming X-Request-Id if the caller supplied one (common when an
        // upstream load balancer or API gateway already assigned one). Otherwise, generate.
        String id = request.getHeaderString(HEADER);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, id);
        // Stash the final id on the request context so the response filter can echo it.
        request.setProperty(MDC_KEY, id);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        Object id = request.getProperty(MDC_KEY);
        if (id != null) {
            response.getHeaders().putSingle(HEADER, id);
        }
        MDC.remove(MDC_KEY);
    }
}

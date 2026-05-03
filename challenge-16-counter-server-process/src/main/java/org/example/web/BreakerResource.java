package org.example.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.resilience.BreakerRegistry;

import java.io.IOException;

/**
 * Admin servlet exposing the state of every CircuitBreaker on this counter
 * instance. Registered on the Dropwizard admin connector at /breakers,
 * reachable per-counter on host ports 18081 / 28081 / 38081 (the same
 * mappings as healthcheck and metrics).
 *
 * Why the admin port (not the application port): breaker state is
 * operational diagnostic data, not a customer-facing API. Same reason
 * /healthcheck and /metrics live here. In production the admin port is
 * firewalled to internal networks; the application port is exposed to
 * users via the LB.
 *
 * Returns a JSON object keyed by breaker name with values "CLOSED", "OPEN",
 * or "HALF_OPEN". Used by the chaos demo in the README to watch breaker
 * transitions in real time.
 *
 * In production this would feed Prometheus → Grafana → "alert if any
 * breaker has been open >5 min." We keep it as a JSON view for inspection.
 */
public class BreakerResource extends HttpServlet {

    private final BreakerRegistry breakers;
    private final ObjectMapper mapper;

    public BreakerResource(BreakerRegistry breakers, ObjectMapper mapper) {
        this.breakers = breakers;
        this.mapper = mapper;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(200);
        resp.setContentType("application/json");
        mapper.writeValue(resp.getOutputStream(), breakers.snapshot());
    }
}

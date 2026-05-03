package org.example.health;

import com.codahale.metrics.health.HealthCheck;
import org.jdbi.v3.core.Jdbi;

/**
 * Verifies DB reachability by running `SELECT 1`. If the DB is down or locked,
 * the /healthcheck endpoint will fail and load balancers know to route around
 * this instance.
 */
public class DatabaseHealthCheck extends HealthCheck {

    private final Jdbi jdbi;

    public DatabaseHealthCheck(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    protected Result check() {
        try {
            Integer one = jdbi.withHandle(h ->
                h.createQuery("SELECT 1").mapTo(Integer.class).one());
            return (one == 1) ? Result.healthy("db reachable") : Result.unhealthy("unexpected result");
        } catch (Exception e) {
            return Result.unhealthy(e);
        }
    }
}

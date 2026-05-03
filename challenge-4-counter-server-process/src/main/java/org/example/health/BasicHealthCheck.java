package org.example.health;

import com.codahale.metrics.health.HealthCheck;

public class BasicHealthCheck extends HealthCheck {

    @Override
    protected Result check() {
        return Result.healthy("challenge 4 counter server is healthy");
    }
}

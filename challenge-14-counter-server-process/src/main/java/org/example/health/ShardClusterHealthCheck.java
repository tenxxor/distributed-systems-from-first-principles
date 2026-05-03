package org.example.health;

import com.codahale.metrics.health.HealthCheck;
import org.example.ShardRouter;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Probes EVERY shard in the cluster. If any shard is unreachable, marks this
 * counter instance unhealthy.
 *
 * Rationale: with sharding-without-replication, losing a shard means we can't
 * serve any operations for the keys that hash to that shard. The instance
 * can't usefully serve traffic when a slice of the keyspace is missing —
 * better to fail fast and let the LB route around to (other) instances that
 * might also be in the same boat (because they share the same Postgres
 * shards), but at least the failure surface is visible in healthchecks.
 */
public class ShardClusterHealthCheck extends HealthCheck {

    private final ShardRouter router;

    public ShardClusterHealthCheck(ShardRouter router) {
        this.router = router;
    }

    @Override
    protected Result check() {
        List<String> failures = new ArrayList<>();
        List<Jdbi> shards = router.allShards();
        for (int i = 0; i < shards.size(); i++) {
            try {
                Integer one = shards.get(i).withHandle(h ->
                        h.createQuery("SELECT 1").mapTo(Integer.class).one());
                if (one == null || one != 1) {
                    failures.add("shard-" + i + ": unexpected SELECT 1 result");
                }
            } catch (Exception e) {
                failures.add("shard-" + i + ": " + e.getMessage());
            }
        }
        if (failures.isEmpty()) {
            return Result.healthy("all " + shards.size() + " shards reachable");
        }
        return Result.unhealthy("shard failures: " + String.join("; ", failures));
    }
}

package org.example;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.example.resilience.BreakerRegistry;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.zip.CRC32;

/**
 * Encapsulates the sharding strategy in one place. Three responsibilities:
 *
 *   1. Given a counterId, decide which shard owns it.
 *   2. Hand back the JDBI for that shard for the helper to use.
 *   3. Wrap shard work in a per-shard CIRCUIT BREAKER (challenge 16).
 *
 * The hash function is deliberately simple — CRC32 of the counterId, modulo
 * the number of shards. CRC32 is uniform enough for a counter ID space and
 * has the nice property of being deterministic and language-agnostic
 * (anyone reproducing this in another language gets the same routing).
 *
 * We use SIMPLE MODULO (not consistent hashing) because we never resize the
 * cluster at runtime — adding a shard would require migrating most data
 * regardless. Production systems with elastic scaling use consistent hashing
 * to limit data movement on resize.
 *
 * The breaker is keyed PER SHARD, not globally — this preserves the failure-
 * domain independence that sharding gave us. A dead shard-1 trips only its
 * own breaker; shard-0 and shard-2 keep serving traffic for counters they own.
 */
public class ShardRouter {

    private static final Logger log = LoggerFactory.getLogger(ShardRouter.class);

    private final List<Jdbi> shardJdbis;
    private final BreakerRegistry breakers;

    public ShardRouter(List<Jdbi> shardJdbis, BreakerRegistry breakers) {
        if (shardJdbis.isEmpty()) {
            throw new IllegalArgumentException("ShardRouter needs at least one shard");
        }
        this.shardJdbis = List.copyOf(shardJdbis);
        this.breakers = breakers;
        log.info("shard.router.init shardCount={}", shardJdbis.size());
    }

    /** Number of shards in the cluster. */
    public int shardCount() {
        return shardJdbis.size();
    }

    /** Pick the shard index for a counter ID. Deterministic — same input always maps to same shard. */
    public int shardIndexFor(String counterId) {
        CRC32 crc = new CRC32();
        crc.update(counterId.getBytes(StandardCharsets.UTF_8));
        // CRC32 returns a long in [0, 2^32); convert to a signed int and abs it
        // before modulo. Math.abs(Integer.MIN_VALUE) is itself negative, so
        // we mask off the sign bit instead — the cleanest portable trick.
        int hash = (int) (crc.getValue() & 0x7FFFFFFF);
        return hash % shardJdbis.size();
    }

    /**
     * Run work on the shard owning this counter, guarded by that shard's breaker.
     * Throws CallNotPermittedException if the breaker is OPEN — the resource
     * layer translates that into 503.
     */
    public <T> T onCounterShard(String counterId, Function<Jdbi, T> work) {
        int idx = shardIndexFor(counterId);
        return onShardIndex(idx, work);
    }

    /** Run work on a specific shard index, guarded by its breaker. */
    public <T> T onShardIndex(int shardIndex, Function<Jdbi, T> work) {
        CircuitBreaker breaker = breakers.forShard(shardIndex);
        Jdbi jdbi = shardJdbis.get(shardIndex);
        return breaker.executeSupplier(() -> work.apply(jdbi));
    }

    /** Number of shards (alias). */
    public List<Jdbi> allShards() {
        return shardJdbis;
    }

    /** Direct access to a shard's JDBI without going through its breaker.
     *  Used by health checks that want to observe state without tripping it. */
    public Jdbi rawShardJdbi(int shardIndex) {
        return shardJdbis.get(shardIndex);
    }
}

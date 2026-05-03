package org.example;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Encapsulates the sharding strategy in one place. Two responsibilities:
 *
 *   1. Given a counterId, decide which shard owns it.
 *   2. Hand back the JDBI for that shard for the helper to use.
 *
 * The hash function is deliberately simple — CRC32 of the counterId, modulo
 * the number of shards. CRC32 is uniform enough for a counter ID space and
 * has the nice property of being deterministic and language-agnostic
 * (anyone reproducing this in another language gets the same routing).
 *
 * We use SIMPLE MODULO (not consistent hashing) because we never resize the
 * cluster at runtime — adding a shard would require migrating most data
 * regardless. Production systems with elastic scaling use consistent hashing
 * to limit data movement on resize. See the README's notes for more.
 */
public class ShardRouter {

    private static final Logger log = LoggerFactory.getLogger(ShardRouter.class);

    private final List<Jdbi> shardJdbis;

    public ShardRouter(List<Jdbi> shardJdbis) {
        if (shardJdbis.isEmpty()) {
            throw new IllegalArgumentException("ShardRouter needs at least one shard");
        }
        this.shardJdbis = List.copyOf(shardJdbis);
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

    /** Get the JDBI for the shard that owns this counter. */
    public Jdbi jdbiFor(String counterId) {
        return shardJdbis.get(shardIndexFor(counterId));
    }

    /** All shard JDBIs, for cross-shard operations like list (scatter-gather). */
    public List<Jdbi> allShards() {
        return shardJdbis;
    }
}

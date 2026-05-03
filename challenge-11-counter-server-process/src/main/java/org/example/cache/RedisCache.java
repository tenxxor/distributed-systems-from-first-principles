package org.example.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Thin wrapper around Lettuce that implements a cache-aside pattern with
 * graceful degradation:
 *
 *   1. On read, try Redis. If hit, return the cached value.
 *   2. On miss (or Redis failure), call the supplier (a DB read) and write
 *      the result back to Redis.
 *   3. On write, the application invalidates the key — the next read will
 *      repopulate it from the DB.
 *
 * "Graceful degradation" means: if Redis is unreachable, we don't fail the
 * request. We just go straight to the DB. Redis is an optimization, not a
 * dependency.
 *
 * Values are JSON-serialized via Jackson — slightly slower than a binary
 * format but human-debuggable via `redis-cli GET <key>`.
 */
public class RedisCache {

    private static final Logger log = LoggerFactory.getLogger(RedisCache.class);

    private final StatefulRedisConnection<String, String> conn;
    private final ObjectMapper mapper;

    public RedisCache(StatefulRedisConnection<String, String> conn, ObjectMapper mapper) {
        this.conn = conn;
        this.mapper = mapper;
    }

    /**
     * Cache-aside read: check Redis first, fall back to the supplier on miss
     * or on Redis error. Successful supplier calls are written back to Redis
     * with the given TTL.
     */
    public <T> T get(String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
        String cached;
        try {
            RedisCommands<String, String> sync = conn.sync();
            cached = sync.get(key);
        } catch (Exception e) {
            log.warn("redis.get.error key={} err={} — falling back to source", key, e.getMessage());
            return loader.get();
        }

        if (cached != null) {
            try {
                return mapper.readValue(cached, type);
            } catch (Exception e) {
                log.warn("redis.deserialize.error key={} err={} — re-fetching", key, e.getMessage());
                // fall through to loader; the bad cache entry will be overwritten below
            }
        }

        T fresh = loader.get();
        if (fresh != null) {
            try {
                String json = mapper.writeValueAsString(fresh);
                conn.sync().set(key, json, SetArgs.Builder.px(ttl.toMillis()));
            } catch (Exception e) {
                log.warn("redis.set.error key={} err={}", key, e.getMessage());
                // Not fatal — the request still succeeds, the next reader will retry.
            }
        }
        return fresh;
    }

    /**
     * Invalidate a key. Called on writes. If Redis is unreachable, log and
     * move on — the entry will eventually expire on its own.
     */
    public void invalidate(String key) {
        try {
            conn.sync().del(key);
        } catch (Exception e) {
            log.warn("redis.invalidate.error key={} err={}", key, e.getMessage());
        }
    }

    /** Optional helper for the {@code OptionalInt}-shaped vote cache. */
    public Optional<Integer> getOptionalInt(String key, Duration ttl, Supplier<Optional<Integer>> loader) {
        try {
            String cached = conn.sync().get(key);
            if (cached != null) {
                if (cached.equals("__none__")) return Optional.empty();
                return Optional.of(Integer.parseInt(cached));
            }
        } catch (Exception e) {
            log.warn("redis.get.error key={} err={}", key, e.getMessage());
            return loader.get();
        }

        Optional<Integer> fresh = loader.get();
        try {
            String value = fresh.map(String::valueOf).orElse("__none__");
            conn.sync().set(key, value, SetArgs.Builder.px(ttl.toMillis()));
        } catch (Exception e) {
            log.warn("redis.set.error key={} err={}", key, e.getMessage());
        }
        return fresh;
    }
}

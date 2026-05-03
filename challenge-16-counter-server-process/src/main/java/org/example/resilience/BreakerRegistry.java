package org.example.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns one CircuitBreaker per dependency. Three groups:
 *
 *   - Per-shard Postgres breakers (shard-0, shard-1, shard-2). Independent —
 *     a dead shard-1 does not trip shard-0 or shard-2. This is what preserves
 *     the failure-domain independence that sharding gave us.
 *
 *   - One cache breaker. Cache is a single instance; one breaker covers it.
 *
 *   - One Kafka publish breaker. Same reasoning.
 *
 * Per-instance state — every counter container has its own registry, no
 * coordination across counters. See README's "What's Missing" for why we
 * intentionally skip distributed-breaker coordination.
 */
public class BreakerRegistry {

    /**
     * Shared sliding-window config for the Postgres shard breakers.
     *
     * Tuned for the demo to flip quickly under load (5 calls minimum, 50%
     * failure rate triggers OPEN, slow-call threshold 2s matches the JDBC
     * socketTimeout). Production would use larger windows for stability.
     */
    private static final CircuitBreakerConfig SHARD_CONFIG = CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .slowCallRateThreshold(50.0f)
            .slowCallDurationThreshold(Duration.ofSeconds(2))
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(2)
            .minimumNumberOfCalls(5)
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(20)
            .recordExceptions(Exception.class)
            .build();

    /** Cache: tighter slow-call threshold (cache should be sub-50ms). */
    private static final CircuitBreakerConfig CACHE_CONFIG = CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .slowCallRateThreshold(50.0f)
            .slowCallDurationThreshold(Duration.ofMillis(500))
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(2)
            .minimumNumberOfCalls(5)
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(20)
            .recordExceptions(Exception.class)
            .build();

    /** Kafka publish: 5s slow-call matches our delivery.timeout.ms. */
    private static final CircuitBreakerConfig KAFKA_CONFIG = CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .slowCallRateThreshold(50.0f)
            .slowCallDurationThreshold(Duration.ofSeconds(5))
            .waitDurationInOpenState(Duration.ofSeconds(15))
            .permittedNumberOfCallsInHalfOpenState(2)
            .minimumNumberOfCalls(5)
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(20)
            .recordExceptions(Exception.class)
            .build();

    private final CircuitBreaker[] shardBreakers;
    private final CircuitBreaker cacheBreaker;
    private final CircuitBreaker kafkaPublishBreaker;

    public BreakerRegistry(int shardCount) {
        this.shardBreakers = new CircuitBreaker[shardCount];
        for (int i = 0; i < shardCount; i++) {
            shardBreakers[i] = CircuitBreaker.of("shard-" + i, SHARD_CONFIG);
        }
        this.cacheBreaker = CircuitBreaker.of("cache", CACHE_CONFIG);
        this.kafkaPublishBreaker = CircuitBreaker.of("kafka-publish", KAFKA_CONFIG);
    }

    public CircuitBreaker forShard(int shardIndex) {
        return shardBreakers[shardIndex];
    }

    public CircuitBreaker forCache() {
        return cacheBreaker;
    }

    public CircuitBreaker forKafkaPublish() {
        return kafkaPublishBreaker;
    }

    /** State snapshot for the /admin/breakers endpoint. Insertion-ordered. */
    public Map<String, String> snapshot() {
        Map<String, String> states = new LinkedHashMap<>();
        for (CircuitBreaker b : shardBreakers) {
            states.put(b.getName(), b.getState().name());
        }
        states.put(cacheBreaker.getName(), cacheBreaker.getState().name());
        states.put(kafkaPublishBreaker.getName(), kafkaPublishBreaker.getState().name());
        return states;
    }
}

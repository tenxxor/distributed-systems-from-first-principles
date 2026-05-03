package org.example.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Trending consumer: maintains a Redis sorted set of counters by recent
 * vote velocity. The "top trending" leaderboard is then queryable in
 * O(log N) via a single Redis ZRANGE.
 *
 * Real-world analog: YouTube/TikTok trending pages, Reddit's "rising,"
 * Twitter trends. Stream-driven derived view — recomputed continuously
 * from the event stream, materialized in fast key-value storage.
 *
 * Implementation: Redis sorted set "trending:counters" — keys are
 * counter IDs, scores are vote counts. ZINCRBY on each event. The top
 * 10 is `ZRANGE trending:counters 0 9 REV WITHSCORES`.
 *
 * In a real implementation you'd want a sliding-window decay (e.g.,
 * scores halve every hour) so brand-new bursts surface above old
 * cumulative leaders. We don't implement that — simple cumulative
 * score is enough to demonstrate the pattern.
 *
 * Replay caveat: replaying this consumer would double-count scores
 * unless you reset the sorted set first. See README's notes on replay
 * with derived state.
 */
public class TrendingConsumer {

    private static final Logger log = LoggerFactory.getLogger(TrendingConsumer.class);

    private static final String TRENDING_KEY = "trending:counters";

    public static void run() {
        String bootstrapServers = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
        String topic = System.getenv().getOrDefault("KAFKA_VOTE_TOPIC", "vote-cast");
        String redisUrl = System.getenv().getOrDefault("REDIS_CACHE_URL", "redis://redis-cache:6379");

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", "trending-consumer-group");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        props.put("auto.offset.reset", "earliest");
        props.put("enable.auto.commit", "true");
        props.put("auto.commit.interval.ms", "1000");

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule());

        log.info("trending-consumer.start bootstrapServers={} topic={} redisUrl={}",
                bootstrapServers, topic, redisUrl);

        RedisClient redisClient = RedisClient.create(RedisURI.create(redisUrl));
        try (StatefulRedisConnection<String, String> redisConn = redisClient.connect();
             KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {

            RedisCommands<String, String> redis = redisConn.sync();
            consumer.subscribe(Collections.singletonList(topic));

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        VoteCastEvent event = mapper.readValue(record.value(), VoteCastEvent.class);
                        // Only LIKE counts toward trending; DISLIKE does not boost.
                        // (Trivial scoring choice; real systems weight by recency,
                        // user reputation, content category, etc.)
                        double delta = "LIKE".equals(event.vote()) ? 1.0 : 0.0;
                        if (delta > 0) {
                            Double newScore = redis.zincrby(TRENDING_KEY, delta, event.counterId());
                            log.info("trending.updated counterId={} score={} eventId={}",
                                    event.counterId(), newScore, event.eventId());
                        } else {
                            log.info("trending.skipped counterId={} vote={} (DISLIKE doesn't trend)",
                                    event.counterId(), event.vote());
                        }

                        // Periodically log the current top 5 so the demo is observable.
                        if (record.offset() % 5 == 0) {
                            List<String> top5 = redis.zrevrange(TRENDING_KEY, 0, 4);
                            log.info("trending.snapshot top5={}", top5);
                        }
                    } catch (Exception e) {
                        log.warn("trending-consumer.record.error partition={} offset={} err={}",
                                record.partition(), record.offset(), e.getMessage());
                    }
                }
            }
        } finally {
            redisClient.shutdown();
        }
    }
}

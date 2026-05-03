package org.example.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Second consumer role on the same vote-cast topic. Same JAR, different
 * group ID, completely independent processing.
 *
 * Group ID: analytics-consumer-group. Sees every event the
 * notification-consumer sees, because they're in different groups —
 * this is the fan-out story Kafka enables and Redis lists don't.
 *
 * What "analytics" means here: count events per counter and emit a log
 * line every 5 events. Not a real analytics pipeline; demonstrates that
 * a *different* consumer can do *different* work on the same stream.
 *
 * Notably this consumer doesn't sleep 500ms per event — its work is fast.
 * If Kafka were a Redis queue (one consumer per task), the analytics work
 * would have to wait for notifications to finish. With Kafka's fan-out,
 * each consumer goes at its own pace.
 */
public class AnalyticsConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsConsumer.class);

    private static final Map<String, AtomicLong> counterEventCounts = new HashMap<>();
    private static long totalEvents = 0;

    public static void run() {
        String bootstrapServers = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
        String topic = System.getenv().getOrDefault("KAFKA_VOTE_TOPIC", "vote-cast");

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", "analytics-consumer-group");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        props.put("auto.offset.reset", "earliest");
        props.put("enable.auto.commit", "true");
        props.put("auto.commit.interval.ms", "1000");

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule());

        log.info("analytics-consumer.start bootstrapServers={} topic={}", bootstrapServers, topic);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        VoteCastEvent event = mapper.readValue(record.value(), VoteCastEvent.class);
                        handleAnalytics(event, record.partition(), record.offset());
                    } catch (Exception e) {
                        log.warn("analytics-consumer.handle.error partition={} offset={} err={}",
                                record.partition(), record.offset(), e.getMessage());
                    }
                }
            }
        }
    }

    private static void handleAnalytics(VoteCastEvent event, int partition, long offset) {
        totalEvents++;
        long perCounter = counterEventCounts
                .computeIfAbsent(event.counterId(), k -> new AtomicLong())
                .incrementAndGet();

        log.info("analytics.event eventId={} partition={} offset={} counterId={} totalEvents={} perCounterEvents={}",
                event.eventId(), partition, offset,
                event.counterId(), totalEvents, perCounter);
    }
}

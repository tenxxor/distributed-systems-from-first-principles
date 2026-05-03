package org.example.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Producer-side wrapper around Kafka. Used by the counter to publish
 * vote-cast events. Stays in this single library because the producer
 * setup is small and Kafka's KafkaProducer is already thread-safe — one
 * instance can be shared across all request threads in the counter.
 *
 * Partition key: counterId. Same key always lands in the same partition,
 * so events about a single counter are strictly ordered. Events for
 * different counters can be processed in parallel by partition.
 */
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper mapper;
    private final String topic;

    public EventPublisher(String bootstrapServers, String topic, ObjectMapper mapper) {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        // acks=1 — leader acks; balance of durability and latency. Production
        // critical paths use acks=all; we don't because we already accept
        // best-effort semantics for events from the producer side.
        props.put("acks", "1");
        props.put("linger.ms", "0");          // send immediately, don't batch
        // Tight timeouts (challenge 16): the breaker can only observe slow/
        // failing publishes if the call actually surfaces the failure within a
        // bounded window. delivery.timeout.ms is the upper bound on how long
        // a single publish can take before the producer gives up. 5s here
        // matches the breaker's slowCallDurationThreshold for kafka-publish.
        props.put("delivery.timeout.ms", "5000");
        props.put("request.timeout.ms", "3000");
        props.put("max.block.ms", "3000");      // bound producer.send() blocking on metadata fetch

        this.producer = new KafkaProducer<>(props);
        this.mapper = mapper;
        this.topic = topic;
        log.info("kafka.producer.init bootstrapServers={} topic={}", bootstrapServers, topic);
    }

    /**
     * Publish a vote event, blocking up to ~5s for the broker to ack.
     *
     * Why we block now (was async-return in ch15): the circuit breaker
     * around publish needs to OBSERVE the outcome to count successes/
     * failures/slow-calls. An async fire-and-forget gives the breaker
     * nothing to measure. We bound the wait at delivery.timeout.ms (5s) so
     * a dead Kafka can't tie up the request thread indefinitely.
     *
     * Throws on failure — CounterHelper wraps this in the kafka-publish
     * breaker; failures count toward tripping the breaker.
     */
    public void publishVoteCast(String counterId, String userId, String vote, int likes, int dislikes) {
        VoteCastEvent event = new VoteCastEvent(
                UUID.randomUUID().toString(),
                counterId, userId, vote, likes, dislikes,
                System.currentTimeMillis()
        );
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (Exception e) {
            // Serialization is a programmer bug, not a transport failure —
            // don't let it count toward tripping the breaker.
            log.error("kafka.publish.serialize.error err={}", e.getMessage());
            return;
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, counterId, json);
        try {
            // send() returns immediately with a Future. We block on the Future
            // up to delivery.timeout.ms+1s so the breaker sees the real outcome.
            Future<RecordMetadata> fut = producer.send(record);
            RecordMetadata metadata = fut.get(6, TimeUnit.SECONDS);
            log.info("kafka.publish.ok eventId={} partition={} offset={}",
                    event.eventId(), metadata.partition(), metadata.offset());
        } catch (Exception e) {
            // Make the breaker count it; CounterHelper.publishBestEffort logs.
            throw new RuntimeException("kafka publish failed: " + e.getMessage(), e);
        }
    }

    public void close() {
        producer.flush();
        producer.close();
    }
}

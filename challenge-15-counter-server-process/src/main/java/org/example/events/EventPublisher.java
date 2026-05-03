package org.example.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.UUID;

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
        // Reasonable defaults; tune in real production.
        props.put("linger.ms", "0");          // send immediately, don't batch
        // delivery.timeout.ms must be >= linger.ms + request.timeout.ms
        // (request.timeout.ms defaults to 30s). 60s gives Kafka time to
        // resolve the broker, accept the message, and ack.
        props.put("delivery.timeout.ms", "60000");
        props.put("request.timeout.ms", "30000");

        this.producer = new KafkaProducer<>(props);
        this.mapper = mapper;
        this.topic = topic;
        log.info("kafka.producer.init bootstrapServers={} topic={}", bootstrapServers, topic);
    }

    /**
     * Publish a vote event. Async — returns immediately with a Future. We
     * don't block the request thread waiting for Kafka to acknowledge; the
     * vote already succeeded in Postgres, the event is best-effort.
     *
     * If Kafka is down, send() throws or the Future fails. We log the failure
     * but don't propagate it — same trade-off as the queue producer in ch 14.
     */
    public void publishVoteCast(String counterId, String userId, String vote, int likes, int dislikes) {
        VoteCastEvent event = new VoteCastEvent(
                UUID.randomUUID().toString(),
                counterId, userId, vote, likes, dislikes,
                System.currentTimeMillis()
        );
        try {
            String json = mapper.writeValueAsString(event);
            // Key = counterId → all events for the same counter go to the same
            // partition → consumers see them in order.
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, counterId, json);
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.warn("kafka.publish.error eventId={} err={}", event.eventId(), exception.getMessage());
                } else {
                    log.info("kafka.publish.ok eventId={} partition={} offset={}",
                            event.eventId(), metadata.partition(), metadata.offset());
                }
            });
        } catch (Exception e) {
            log.error("kafka.publish.serialize.error err={}", e.getMessage());
        }
    }

    public void close() {
        producer.flush();
        producer.close();
    }
}

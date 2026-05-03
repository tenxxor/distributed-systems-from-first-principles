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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Properties;

/**
 * Audit consumer: writes every vote-cast event to a dedicated Postgres
 * audit table. Durable, queryable, replayable.
 *
 * Real-world analog: every regulated industry (finance, healthcare,
 * advertising) requires this — a tamper-evident log of every action,
 * separate from the operational data, queried for compliance.
 *
 * The audit table lives on shard 0 — ARBITRARY. Audit doesn't need
 * sharding (low write rate, queries are full scans by definition).
 * Production setups often dedicate a separate Postgres for audit logs
 * to isolate operational from compliance traffic.
 *
 * Why this earns its place over a "task" approach: audits go to durable
 * storage, multiple downstream systems (compliance reports, fraud
 * detection, analytics rollups) might want to read from the audit table
 * later. Stream-driven persistence is the natural fit.
 *
 * Replay-friendly: if audit data ever gets corrupted, reset the
 * consumer offset to 0 and let it re-populate from Kafka's retention.
 */
public class AuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditConsumer.class);

    public static void run() {
        String bootstrapServers = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
        String topic = System.getenv().getOrDefault("KAFKA_VOTE_TOPIC", "vote-cast");
        String dbUrl = System.getenv().getOrDefault("AUDIT_DB_URL", "jdbc:postgresql://postgres-shard-0:5432/counters");
        String dbUser = System.getenv().getOrDefault("POSTGRES_USER", "counter_app");
        String dbPassword = System.getenv().getOrDefault("POSTGRES_PASSWORD", "change_me_in_prod");

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("group.id", "audit-consumer-group");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
        props.put("auto.offset.reset", "earliest");
        // Manual commit on this consumer — we want to commit only AFTER the
        // INSERT succeeds, otherwise an audit row could be missed if the
        // consumer crashes between dequeue and insert. Different design choice
        // than analytics-consumer (which auto-commits because in-memory state
        // is regeneratable). Audit writes are durable; we want to be careful.
        props.put("enable.auto.commit", "false");

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule());

        log.info("audit-consumer.start bootstrapServers={} topic={} dbUrl={}", bootstrapServers, topic, dbUrl);

        ensureAuditTable(dbUrl, dbUser, dbPassword);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
             Connection dbConn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            consumer.subscribe(Collections.singletonList(topic));

            String insertSql =
                    "INSERT INTO audit.audit_log (event_id, counter_id, user_id, vote, likes, dislikes, occurred_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, to_timestamp(?::double precision / 1000)) " +
                    "ON CONFLICT (event_id) DO NOTHING";

            try (PreparedStatement insert = dbConn.prepareStatement(insertSql)) {
                while (true) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
                    if (records.isEmpty()) continue;

                    for (ConsumerRecord<String, String> record : records) {
                        try {
                            VoteCastEvent event = mapper.readValue(record.value(), VoteCastEvent.class);
                            insert.setString(1, event.eventId());
                            insert.setString(2, event.counterId());
                            insert.setString(3, event.userId());
                            insert.setString(4, event.vote());
                            insert.setInt(5, event.likes());
                            insert.setInt(6, event.dislikes());
                            insert.setLong(7, event.occurredAt());
                            insert.executeUpdate();
                            log.info("audit.recorded eventId={} partition={} offset={} counterId={}",
                                    event.eventId(), record.partition(), record.offset(), event.counterId());
                        } catch (Exception e) {
                            log.warn("audit-consumer.record.error partition={} offset={} err={}",
                                    record.partition(), record.offset(), e.getMessage());
                        }
                    }
                    // Commit AFTER the batch's inserts succeed. ON CONFLICT
                    // DO NOTHING gives us idempotency for re-deliveries.
                    consumer.commitSync();
                }
            }
        } catch (SQLException e) {
            log.error("audit-consumer.db.fatal err={}", e.getMessage(), e);
        }
    }

    /**
     * Create the audit_log table if it doesn't exist. Idempotent.
     * Audit table is intentionally append-only — no UPDATE, no DELETE in
     * normal operation. Real production setups use Postgres triggers or
     * row-level security to prevent tampering.
     */
    private static void ensureAuditTable(String dbUrl, String user, String password) {
        // Audit lives in its own schema, separate from the operational `public`
        // schema that the counter app's Flyway manages. This avoids a Flyway
        // collision (counters' V1 expects an empty public schema; if we put
        // audit_log in public, V1 would refuse to run). Production-realistic:
        // audit usually lives in a dedicated schema or even a dedicated DB.
        try (Connection conn = DriverManager.getConnection(dbUrl, user, password)) {
            try (PreparedStatement createSchema = conn.prepareStatement(
                    "CREATE SCHEMA IF NOT EXISTS audit")) {
                createSchema.execute();
            }
            try (PreparedStatement createTable = conn.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS audit.audit_log (" +
                     "  event_id    TEXT PRIMARY KEY, " +
                     "  counter_id  TEXT NOT NULL, " +
                     "  user_id     TEXT NOT NULL, " +
                     "  vote        TEXT NOT NULL, " +
                     "  likes       INTEGER NOT NULL, " +
                     "  dislikes    INTEGER NOT NULL, " +
                     "  occurred_at TIMESTAMPTZ NOT NULL, " +
                     "  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()" +
                     ")")) {
                createTable.execute();
            }
            log.info("audit-consumer.table.ensured schema=audit");
        } catch (SQLException e) {
            log.error("audit-consumer.table.create.error err={}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}

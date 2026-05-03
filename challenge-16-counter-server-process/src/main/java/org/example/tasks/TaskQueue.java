package org.example.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.KeyValue;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin wrapper around Redis lists implementing a task queue.
 *
 * Producer side (in counter): {@link #enqueue(TaskMessage)} — RPUSH onto
 * the main queue. Returns immediately.
 *
 * Consumer side (in worker): {@link #blockingDequeue(Duration)} — BLPOP
 * waits up to `timeout` for a task. Returns the task if any, empty otherwise.
 *
 * Failure handling:
 *   - On a recoverable failure (network, transient downstream issue), the
 *     worker calls {@link #requeueWithRetry} which RPUSHes a copy of the
 *     task with attempts incremented back onto the main queue.
 *   - When attempts ≥ MAX_ATTEMPTS, the worker calls {@link #deadLetter}
 *     which RPUSHes onto the dead-letter queue. Operators can inspect or
 *     replay these manually.
 *
 * Semantics: at-least-once delivery. The worker can crash AFTER processing a
 * task but BEFORE acknowledging — in that case the task is gone (we use
 * BLPOP, which removes the item from the list immediately on dequeue). To
 * make this stricter (no loss on worker crash mid-processing), real systems
 * use BRPOPLPUSH to atomically move the task to a "processing" list, then
 * remove from "processing" on success. We don't, for simplicity. Notes
 * entry covers this trade-off.
 */
public class TaskQueue {

    private static final Logger log = LoggerFactory.getLogger(TaskQueue.class);

    public static final String QUEUE_KEY       = "tasks:notifications";
    public static final String DEAD_LETTER_KEY = "tasks:notifications:dead-letter";
    public static final int    MAX_ATTEMPTS    = 3;

    private final StatefulRedisConnection<String, String> conn;
    private final ObjectMapper mapper;

    public TaskQueue(StatefulRedisConnection<String, String> conn, ObjectMapper mapper) {
        this.conn = conn;
        this.mapper = mapper;
    }

    /** Producer: push a fresh task onto the main queue. */
    public void enqueue(String type, TaskMessage.VoteNotificationPayload payload) {
        TaskMessage msg = new TaskMessage(
                UUID.randomUUID().toString(),
                type,
                payload,
                0,                         // first attempt
                System.currentTimeMillis()
        );
        try {
            String json = mapper.writeValueAsString(msg);
            conn.sync().rpush(QUEUE_KEY, json);
            log.info("task.enqueued taskId={} type={} queueDepth={}",
                    msg.taskId(), type, conn.sync().llen(QUEUE_KEY));
        } catch (Exception e) {
            // We don't fail the user-facing request because of a queue write
            // failure — that defeats the point of decoupling. Log and continue.
            // Real production would wire this into metrics + alerting; the
            // user's vote DID succeed, only the side effect didn't enqueue.
            log.error("task.enqueue.error type={} err={}", type, e.getMessage());
        }
    }

    /** Consumer: block waiting for a task, return it (or empty on timeout). */
    public Optional<TaskMessage> blockingDequeue(Duration timeout) {
        try {
            // BLPOP returns the (queue-name, value) pair or null on timeout.
            // Lettuce's bRPop / bLPop signature uses seconds-as-double.
            KeyValue<String, String> kv = conn.sync().blpop(timeout.toSeconds(), QUEUE_KEY);
            if (kv == null || !kv.hasValue()) return Optional.empty();
            return Optional.of(mapper.readValue(kv.getValue(), TaskMessage.class));
        } catch (Exception e) {
            log.warn("task.dequeue.error err={}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Recoverable failure: push a copy of the task with attempts+1 back onto
     * the main queue. If the new attempts count exceeds the cap, we dead-letter
     * instead. Returns true if requeued, false if dead-lettered.
     */
    public boolean requeueWithRetry(TaskMessage msg) {
        TaskMessage retry = msg.withIncrementedAttempts();
        if (retry.attempts() >= MAX_ATTEMPTS) {
            deadLetter(retry);
            return false;
        }
        try {
            String json = mapper.writeValueAsString(retry);
            // RPUSH appends to the tail — task goes to the back of the line.
            // (For "retry sooner" semantics you'd push to the head with LPUSH;
            // ours retries fairly behind newer tasks.)
            conn.sync().rpush(QUEUE_KEY, json);
            log.info("task.requeued taskId={} attempts={}/{}",
                    retry.taskId(), retry.attempts(), MAX_ATTEMPTS);
            return true;
        } catch (Exception e) {
            log.error("task.requeue.error taskId={} err={}", retry.taskId(), e.getMessage());
            return false;
        }
    }

    /** Push a task onto the dead-letter list. Tasks here require manual intervention. */
    public void deadLetter(TaskMessage msg) {
        try {
            String json = mapper.writeValueAsString(msg);
            conn.sync().rpush(DEAD_LETTER_KEY, json);
            log.warn("task.dead-letter taskId={} attempts={}", msg.taskId(), msg.attempts());
        } catch (Exception e) {
            log.error("task.dead-letter.error taskId={} err={}", msg.taskId(), e.getMessage());
        }
    }

    /** Inspection helper: how many tasks are waiting? */
    public long pendingCount() {
        try {
            return conn.sync().llen(QUEUE_KEY);
        } catch (Exception e) {
            return -1;
        }
    }
}

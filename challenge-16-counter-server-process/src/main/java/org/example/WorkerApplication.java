package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.example.tasks.NotificationHandler;
import org.example.tasks.TaskMessage;
import org.example.tasks.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * Worker process. Long-running loop that:
 *   1. BLPOPs the next task off the queue (blocks up to 5s waiting).
 *   2. Hands it to NotificationHandler.
 *   3. On success, the task is gone (BLPOP already removed it).
 *   4. On failure (RuntimeException), requeues with attempts+1.
 *   5. Repeats forever.
 *
 * Runs as a separate container — same JAR as the counter, different entry
 * point. Multiple worker containers can run concurrently; Redis BLPOP is
 * atomic, so each task is delivered to exactly one worker (competing-consumers
 * pattern).
 */
public class WorkerApplication {

    private static final Logger log = LoggerFactory.getLogger(WorkerApplication.class);
    private static final Duration BLPOP_TIMEOUT = Duration.ofSeconds(5);

    public static void runWorker() {
        // Read connection details from env. Mirrors what Dropwizard's
        // ${ENV:-default} substitution does for the server config.
        String queueRedisUrl = System.getenv().getOrDefault(
                "QUEUE_REDIS_URL", "redis://redis-queue:6379");

        log.info("worker.start queueRedisUrl={}", queueRedisUrl);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule());

        RedisURI uri = RedisURI.create(queueRedisUrl);
        // Long timeout — BLPOP itself can hold the connection for BLPOP_TIMEOUT
        // seconds. Keep this comfortably above that.
        uri.setTimeout(Duration.ofSeconds(30));
        RedisClient client = RedisClient.create(uri);
        StatefulRedisConnection<String, String> conn = client.connect();

        TaskQueue queue = new TaskQueue(conn, mapper);
        NotificationHandler handler = new NotificationHandler();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("worker.shutting-down");
            conn.close();
            client.shutdown();
        }));

        // Main loop. Forever-block on BLPOP, process, repeat.
        while (true) {
            Optional<TaskMessage> taskOpt = queue.blockingDequeue(BLPOP_TIMEOUT);
            if (taskOpt.isEmpty()) {
                // No task arrived in the timeout window. Loop and try again.
                // (We don't exit on idleness — the worker is meant to run forever.)
                continue;
            }
            TaskMessage task = taskOpt.get();
            try {
                handler.handle(task);
            } catch (Exception e) {
                log.warn("worker.task.failed taskId={} attempts={} err={}",
                        task.taskId(), task.attempts(), e.getMessage());
                queue.requeueWithRetry(task);
            }
        }
    }
}

package org.example.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Library that processes one TaskMessage. Stand-in for whatever the real
 * downstream side effect would be — sending email, posting to Slack, calling
 * a webhook, writing to an audit log, etc.
 *
 * For our demo:
 *   - Each notification takes ~500ms (synthetic sleep) to mimic a slow
 *     downstream service. This is what makes the "queue helps with latency"
 *     story tangible: the user-facing vote returns in a few ms, but the
 *     notification takes half a second on the side.
 *   - A magic counterId prefix ("trigger-failure-") forces a synthetic
 *     failure, exercising the retry + dead-letter path.
 */
public class NotificationHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationHandler.class);

    /** Throws RuntimeException on synthetic failure paths to exercise retries. */
    public void handle(TaskMessage msg) {
        TaskMessage.VoteNotificationPayload p = msg.payload();
        long ageMs = System.currentTimeMillis() - msg.enqueuedAt();

        log.info("notification.start taskId={} attempt={}/{} counterId={} userId={} vote={} ageInQueueMs={}",
                msg.taskId(), msg.attempts() + 1, TaskQueue.MAX_ATTEMPTS,
                p.counterId(), p.userId(), p.vote(), ageMs);

        // Synthetic failure path — counterIds starting with "trigger-failure-"
        // always throw, so we can show the retry + dead-letter behavior
        // without needing a real downstream that flakes.
        if (p.counterId() != null && p.counterId().startsWith("trigger-failure-")) {
            throw new RuntimeException("synthetic notification failure for " + p.counterId());
        }

        // Synthetic latency — represents the real cost of a downstream side
        // effect: SMTP, third-party API, etc. THIS is the latency the user
        // would have paid synchronously without the queue.
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        log.info("notification.delivered taskId={} counterId={} userId={} likes={} dislikes={}",
                msg.taskId(), p.counterId(), p.userId(), p.likes(), p.dislikes());
    }
}

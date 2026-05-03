package org.example.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Model representing one queued task.
 *
 * Serialized to JSON when pushed onto the Redis queue and deserialized when
 * popped on the worker side. Pure data — no behavior. By the framework's
 * model/library split, this is unambiguously a model.
 *
 * Fields:
 *   - taskId: a unique identifier (UUID), useful for deduplication and tracing.
 *   - type: the kind of task (we only have "vote-notification" today, but the
 *     enum makes it easy to add more without changing the queue protocol).
 *   - payload: a free-form JSON object with task-specific data. For our
 *     vote-notification, it carries counterId, userId, vote.
 *   - attempts: how many times the worker has tried this task. Starts at 0.
 *     Incremented on retry. After MAX_ATTEMPTS, the task is dead-lettered.
 *   - enqueuedAt: epoch-millis when first put on the queue. Useful for
 *     measuring how long tasks sit in queue.
 */
public record TaskMessage(
        String taskId,
        String type,
        VoteNotificationPayload payload,
        int attempts,
        long enqueuedAt
) {

    @JsonCreator
    public TaskMessage(
            @JsonProperty("taskId")     String taskId,
            @JsonProperty("type")       String type,
            @JsonProperty("payload")    VoteNotificationPayload payload,
            @JsonProperty("attempts")   int attempts,
            @JsonProperty("enqueuedAt") long enqueuedAt
    ) {
        this.taskId = taskId;
        this.type = type;
        this.payload = payload;
        this.attempts = attempts;
        this.enqueuedAt = enqueuedAt;
    }

    /** Return a copy with attempts incremented — for retries. */
    public TaskMessage withIncrementedAttempts() {
        return new TaskMessage(taskId, type, payload, attempts + 1, enqueuedAt);
    }

    /** The payload shape for vote-notification tasks. */
    public record VoteNotificationPayload(
            String counterId,
            String userId,
            String vote,
            int likes,
            int dislikes
    ) {
        @JsonCreator
        public VoteNotificationPayload(
                @JsonProperty("counterId") String counterId,
                @JsonProperty("userId")    String userId,
                @JsonProperty("vote")      String vote,
                @JsonProperty("likes")     int likes,
                @JsonProperty("dislikes")  int dislikes
        ) {
            this.counterId = counterId;
            this.userId = userId;
            this.vote = vote;
            this.likes = likes;
            this.dislikes = dislikes;
        }
    }
}

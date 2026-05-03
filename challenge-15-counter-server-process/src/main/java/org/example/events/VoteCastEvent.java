package org.example.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Event model: "a vote was cast on this counter."
 *
 * Difference from challenge 14's TaskMessage in framing, not just content:
 *   - TaskMessage was a *task* — "do this work."
 *   - VoteCastEvent is a *fact* — "this thing happened."
 *
 * In a task queue, the producer was telling exactly one consumer what to do.
 * In an event stream, the producer announces a fact to nobody in particular —
 * any number of consumers can subscribe and react. The shape of the data
 * happens to be similar; the semantic shift is fundamental.
 *
 * No `attempts` field here (unlike TaskMessage): event consumers track their
 * own offsets in Kafka. If a consumer's processing fails, it doesn't advance
 * its offset; on restart it re-reads from where it left off. Retries are
 * implicit in the offset model rather than explicit in the message.
 */
public record VoteCastEvent(
        String eventId,
        String counterId,
        String userId,
        String vote,
        int likes,
        int dislikes,
        long occurredAt
) {

    @JsonCreator
    public VoteCastEvent(
            @JsonProperty("eventId")    String eventId,
            @JsonProperty("counterId")  String counterId,
            @JsonProperty("userId")     String userId,
            @JsonProperty("vote")       String vote,
            @JsonProperty("likes")      int likes,
            @JsonProperty("dislikes")   int dislikes,
            @JsonProperty("occurredAt") long occurredAt
    ) {
        this.eventId = eventId;
        this.counterId = counterId;
        this.userId = userId;
        this.vote = vote;
        this.likes = likes;
        this.dislikes = dislikes;
        this.occurredAt = occurredAt;
    }
}

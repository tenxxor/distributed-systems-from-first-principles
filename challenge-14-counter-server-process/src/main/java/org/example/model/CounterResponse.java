package org.example.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.example.Counter;

public class CounterResponse {

    private final String counterId;
    private final int likes;
    private final int dislikes;
    private final int score;
    private final long createdAt;

    public CounterResponse(String counterId, int likes, int dislikes, int score, long createdAt) {
        this.counterId = counterId;
        this.likes = likes;
        this.dislikes = dislikes;
        this.score = score;
        this.createdAt = createdAt;
    }

    public static CounterResponse from(Counter c) {
        return new CounterResponse(c.getCounterId(), c.getLikes(), c.getDislikes(), c.getScore(), c.getCreatedAt());
    }

    @JsonProperty public String getCounterId() { return counterId; }
    @JsonProperty public int getLikes() { return likes; }
    @JsonProperty public int getDislikes() { return dislikes; }
    @JsonProperty public int getScore() { return score; }
    @JsonProperty public long getCreatedAt() { return createdAt; }
}

package org.example.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.example.Counter;

/**
 * JSON representation of a counter's aggregate state, returned by the REST API.
 * Separate from the Counter model so that the wire shape can evolve independently
 * of the stored shape.
 */
public class CounterResponse {

    private final String counterId;
    private final int likes;
    private final int dislikes;
    private final int score;

    public CounterResponse(String counterId, int likes, int dislikes, int score) {
        this.counterId = counterId;
        this.likes = likes;
        this.dislikes = dislikes;
        this.score = score;
    }

    public static CounterResponse from(Counter c) {
        return new CounterResponse(c.getCounterId(), c.getLikes(), c.getDislikes(), c.getScore());
    }

    @JsonProperty public String getCounterId() { return counterId; }
    @JsonProperty public int getLikes() { return likes; }
    @JsonProperty public int getDislikes() { return dislikes; }
    @JsonProperty public int getScore() { return score; }
}

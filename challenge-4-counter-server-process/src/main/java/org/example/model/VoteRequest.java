package org.example.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.example.UserVote;

/**
 * JSON body accepted by PUT /api/v1/counters/{id}/vote. Expected shape: {"vote": "like" | "dislike"}.
 */
public class VoteRequest {

    private final String vote;

    @JsonCreator
    public VoteRequest(@JsonProperty("vote") String vote) {
        this.vote = vote;
    }

    @JsonProperty
    public String getVote() {
        return vote;
    }

    /** Parses the vote string into our enum. Throws IllegalArgumentException on invalid input. */
    public UserVote.Vote toVote() {
        if (vote == null) {
            throw new IllegalArgumentException("vote is required (like|dislike)");
        }
        switch (vote.toLowerCase()) {
            case "like":    return UserVote.Vote.LIKE;
            case "dislike": return UserVote.Vote.DISLIKE;
            default: throw new IllegalArgumentException("vote must be 'like' or 'dislike'");
        }
    }
}

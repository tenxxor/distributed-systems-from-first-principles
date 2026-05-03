package org.example.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.example.UserVote;

public class MyVoteResponse {

    private final String userId;
    private final String counterId;
    private final String vote;

    public MyVoteResponse(String userId, String counterId, UserVote.Vote vote) {
        this.userId = userId;
        this.counterId = counterId;
        this.vote = (vote == null) ? null : vote.name().toLowerCase();
    }

    @JsonProperty public String getUserId() { return userId; }
    @JsonProperty public String getCounterId() { return counterId; }
    @JsonProperty public String getVote() { return vote; }
}

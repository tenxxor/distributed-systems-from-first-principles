package org.example;

public class UserVote {

    public enum Vote { LIKE, DISLIKE }

    private final String counterId;
    private final String userId;
    private Vote vote;

    public UserVote(String counterId, String userId, Vote vote) {
        this.counterId = counterId;
        this.userId = userId;
        this.vote = vote;
    }

    public String getCounterId() {
        return counterId;
    }

    public String getUserId() {
        return userId;
    }

    public Vote getVote() {
        return vote;
    }

    public void setVote(Vote vote) {
        this.vote = vote;
    }
}

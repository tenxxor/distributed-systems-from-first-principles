package org.example;

/**
 * Internal domain model for one user's vote on one counter.
 *
 * Vote enum has LIKE and DISLIKE only. Absence of a UserVote means "no vote";
 * we don't store a NONE / cleared value. Same design as challenges 2+.
 */
public class UserVote {

    public enum Vote { LIKE, DISLIKE }

    private final String counterId;
    private final String userId;
    private final Vote vote;

    public UserVote(String counterId, String userId, Vote vote) {
        this.counterId = counterId;
        this.userId = userId;
        this.vote = vote;
    }

    public String getCounterId() { return counterId; }
    public String getUserId() { return userId; }
    public Vote getVote() { return vote; }
}

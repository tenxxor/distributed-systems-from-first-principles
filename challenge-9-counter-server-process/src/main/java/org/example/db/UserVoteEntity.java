package org.example.db;

/**
 * One row from the user_votes table. Vote is stored as an integer: 1 = LIKE, -1 = DISLIKE.
 * We don't store NONE / cleared votes — absence of a row means "no vote."
 */
public record UserVoteEntity(String counterId, String userId, int vote) {}

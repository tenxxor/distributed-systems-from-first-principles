package org.example.db;

/**
 * One row from the counters table.
 *
 * Added in challenge 7: createdAt — the timestamp that makes keyset pagination
 * possible. Stored as seconds since Unix epoch (a long), mapped from the
 * created_at INTEGER column.
 */
public record CounterEntity(String counterId, int likes, int dislikes, long createdAt) {}

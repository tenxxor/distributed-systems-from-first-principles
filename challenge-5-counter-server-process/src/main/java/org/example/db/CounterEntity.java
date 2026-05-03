package org.example.db;

/**
 * One row from the counters table. A DB-row shape, separate from the Counter domain model.
 * Kept as a Java record since it's immutable, pure data.
 */
public record CounterEntity(String counterId, int likes, int dislikes) {}

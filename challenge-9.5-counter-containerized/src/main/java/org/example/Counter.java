package org.example;

/**
 * Internal domain model — the shape the application reasons about.
 * Separate from the CounterEntity (one DB row) and CounterResponse (HTTP body).
 *
 * Added in challenge 7: createdAt (seconds since epoch). Needed as the
 * ordering key for keyset pagination.
 */
public class Counter {

    private final String counterId;
    private int likes;
    private int dislikes;
    private final long createdAt;

    public Counter(String counterId, int likes, int dislikes, long createdAt) {
        this.counterId = counterId;
        this.likes = likes;
        this.dislikes = dislikes;
        this.createdAt = createdAt;
    }

    public String getCounterId() { return counterId; }
    public int getLikes() { return likes; }
    public int getDislikes() { return dislikes; }
    public int getScore() { return likes - dislikes; }
    public long getCreatedAt() { return createdAt; }
}

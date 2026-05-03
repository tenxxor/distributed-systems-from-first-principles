package org.example;

/**
 * Internal domain model — the shape the application reasons about.
 * Separate from the CounterEntity (one DB row) and CounterResponse (HTTP body).
 */
public class Counter {

    private final String counterId;
    private int likes;
    private int dislikes;

    public Counter(String counterId, int likes, int dislikes) {
        this.counterId = counterId;
        this.likes = likes;
        this.dislikes = dislikes;
    }

    public String getCounterId() { return counterId; }
    public int getLikes() { return likes; }
    public int getDislikes() { return dislikes; }
    public int getScore() { return likes - dislikes; }
}

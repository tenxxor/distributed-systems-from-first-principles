package org.example;

public class Counter {

    private final String counterId;
    private int likes;
    private int dislikes;

    public Counter(String counterId) {
        this.counterId = counterId;
    }

    public String getCounterId() {
        return counterId;
    }

    public int getLikes() {
        return likes;
    }

    public void setLikes(int likes) {
        this.likes = likes;
    }

    public int getDislikes() {
        return dislikes;
    }

    public void setDislikes(int dislikes) {
        this.dislikes = dislikes;
    }

    public int getScore() {
        return likes - dislikes;
    }
}

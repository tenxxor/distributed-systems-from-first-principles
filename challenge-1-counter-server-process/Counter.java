public class Counter {

    public enum Vote { LIKE, DISLIKE, NONE }

    private Vote myVote = Vote.NONE;

    public Vote getMyVote() {
        return myVote;
    }

    public void setMyVote(Vote vote) {
        this.myVote = vote;
    }
}

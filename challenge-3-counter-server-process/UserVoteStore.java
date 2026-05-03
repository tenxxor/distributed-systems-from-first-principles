import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UserVoteStore {

    private final Map<String, UserVote> votes = new ConcurrentHashMap<>();

    private static String keyOf(String counterId, String userId) {
        return counterId + "::" + userId;
    }

    public UserVote get(String counterId, String userId) {
        return votes.get(keyOf(counterId, userId));
    }

    public boolean has(String counterId, String userId) {
        return votes.containsKey(keyOf(counterId, userId));
    }

    public void put(UserVote vote) {
        votes.put(keyOf(vote.getCounterId(), vote.getUserId()), vote);
    }

    public void remove(String counterId, String userId) {
        votes.remove(keyOf(counterId, userId));
    }

    public int countByCounterAndVote(String counterId, UserVote.Vote voteType) {
        int count = 0;
        for (UserVote v : votes.values()) {
            if (v.getCounterId().equals(counterId) && v.getVote() == voteType) {
                count++;
            }
        }
        return count;
    }

    public void removeByCounter(String counterId) {
        votes.entrySet().removeIf(e -> e.getValue().getCounterId().equals(counterId));
    }

    public boolean isEmpty() {
        return votes.isEmpty();
    }
}

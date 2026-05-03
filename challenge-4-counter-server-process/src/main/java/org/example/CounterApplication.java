package org.example;

import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import org.example.health.BasicHealthCheck;

public class CounterApplication extends Application<CounterConfiguration> {

    public static void main(String[] args) throws Exception {
        new CounterApplication().run(args);
    }

    @Override
    public void initialize(Bootstrap<CounterConfiguration> bootstrap) {
        // Serve the static HTML frontend at the root URL.
        // The REST API lives under /api/* (see config.yml's server.rootPath).
        bootstrap.addBundle(new AssetsBundle("/assets", "/", "index.html"));
    }

    @Override
    public void run(CounterConfiguration configuration, Environment environment) {
        // Create the stores and the helper, then seed initial data.
        CounterStore counters = new CounterStore();
        UserVoteStore votes = new UserVoteStore();
        CounterHelper helper = new CounterHelper(counters, votes);

        counters.add("video-funny-cats",    new Counter("video-funny-cats"));
        counters.add("video-dev-tutorial",  new Counter("video-dev-tutorial"));
        counters.add("video-music-mix",     new Counter("video-music-mix"));

        votes.put(new UserVote("video-funny-cats",   "alice", UserVote.Vote.LIKE));
        votes.put(new UserVote("video-funny-cats",   "bob",   UserVote.Vote.LIKE));
        votes.put(new UserVote("video-dev-tutorial", "alice", UserVote.Vote.LIKE));
        votes.put(new UserVote("video-dev-tutorial", "bob",   UserVote.Vote.DISLIKE));

        for (var e : counters.entries()) {
            Counter c = e.getValue();
            c.setLikes(votes.countByCounterAndVote(c.getCounterId(), UserVote.Vote.LIKE));
            c.setDislikes(votes.countByCounterAndVote(c.getCounterId(), UserVote.Vote.DISLIKE));
        }

        environment.jersey().register(new CounterResource(helper));
        environment.healthChecks().register("basic", new BasicHealthCheck());
    }
}

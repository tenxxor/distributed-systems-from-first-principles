package org.example;

import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.jdbi3.JdbiFactory;
import org.example.db.CountersDAO;
import org.example.db.UserVotesDAO;
import org.example.health.DatabaseHealthCheck;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

public class CounterApplication extends Application<CounterConfiguration> {

    public static void main(String[] args) throws Exception {
        new CounterApplication().run(args);
    }

    @Override
    public void initialize(Bootstrap<CounterConfiguration> bootstrap) {
        bootstrap.addBundle(new AssetsBundle("/assets", "/", "index.html"));
    }

    @Override
    public void run(CounterConfiguration configuration, Environment environment) {
        Jdbi jdbi = new JdbiFactory().build(environment, configuration.getDatabase(), "sqlite");
        jdbi.installPlugin(new SqlObjectPlugin());

        initializeDatabase(jdbi);

        CounterHelper helper = new CounterHelper(jdbi);

        environment.jersey().register(new CounterResource(helper));
        environment.healthChecks().register("db", new DatabaseHealthCheck(jdbi));
    }

    private void initializeDatabase(Jdbi jdbi) {
        jdbi.useHandle(h -> {
            h.execute("PRAGMA journal_mode=WAL");
            h.execute("PRAGMA synchronous=NORMAL");
            h.execute("PRAGMA busy_timeout=3000");

            h.execute("""
                    CREATE TABLE IF NOT EXISTS counters (
                        counter_id TEXT PRIMARY KEY,
                        likes      INTEGER NOT NULL DEFAULT 0,
                        dislikes   INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            h.execute("""
                    CREATE TABLE IF NOT EXISTS user_votes (
                        counter_id TEXT NOT NULL,
                        user_id    TEXT NOT NULL,
                        vote       INTEGER NOT NULL CHECK (vote IN (-1, 1)),
                        PRIMARY KEY (counter_id, user_id)
                    )
                    """);
            h.execute("CREATE INDEX IF NOT EXISTS idx_user_votes_counter_id ON user_votes(counter_id)");
        });

        // Seed if the counters table is empty (first run only).
        jdbi.useTransaction(h -> {
            CountersDAO cDao = h.attach(CountersDAO.class);
            if (!cDao.listAll().isEmpty()) return;

            cDao.insert("video-funny-cats");
            cDao.insert("video-dev-tutorial");
            cDao.insert("video-music-mix");

            UserVotesDAO vDao = h.attach(UserVotesDAO.class);
            vDao.upsert("video-funny-cats",   "alice",  1);
            vDao.upsert("video-funny-cats",   "bob",    1);
            vDao.upsert("video-dev-tutorial", "alice",  1);
            vDao.upsert("video-dev-tutorial", "bob",   -1);

            cDao.recomputeAggregates("video-funny-cats");
            cDao.recomputeAggregates("video-dev-tutorial");
            cDao.recomputeAggregates("video-music-mix");
        });
    }
}

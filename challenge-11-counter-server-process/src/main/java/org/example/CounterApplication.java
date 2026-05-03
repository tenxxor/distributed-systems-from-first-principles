package org.example;

import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.jdbi3.JdbiFactory;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import org.example.cache.RedisCache;
import org.example.health.DatabaseHealthCheck;
import org.example.web.RequestIdFilter;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CounterApplication extends Application<CounterConfiguration> {

    private static final Logger log = LoggerFactory.getLogger(CounterApplication.class);

    public static void main(String[] args) throws Exception {
        new CounterApplication().run(args);
    }

    @Override
    public void initialize(Bootstrap<CounterConfiguration> bootstrap) {
        bootstrap.addBundle(new AssetsBundle("/assets", "/", "index.html"));
        bootstrap.setConfigurationSourceProvider(
                new SubstitutingSourceProvider(
                        bootstrap.getConfigurationSourceProvider(),
                        new EnvironmentVariableSubstitutor(false)));
    }

    @Override
    public void run(CounterConfiguration configuration, Environment environment) {
        runMigrations(configuration.getDatabase());

        Jdbi jdbi = new JdbiFactory().build(environment, configuration.getDatabase(), "postgresql");
        jdbi.installPlugin(new SqlObjectPlugin());

        // Lettuce connection. The shared RedisClient is heavy; the connection
        // is multiplexed and thread-safe — one connection serves all request
        // threads on this counter process.
        //
        // The 500ms command timeout is critical: if Redis is unreachable, we
        // want the cache call to fail FAST so the cache-aside fallback can
        // hit the DB before the LB's request timeout fires. Lettuce's default
        // is 60s, which would cause user-visible 504s during a Redis outage.
        RedisURI redisUri = RedisURI.create(configuration.getRedis().getUrl());
        redisUri.setTimeout(Duration.ofMillis(500));
        RedisClient redisClient = RedisClient.create(redisUri);
        StatefulRedisConnection<String, String> redisConnection = redisClient.connect();
        environment.lifecycle().manage(new io.dropwizard.lifecycle.Managed() {
            @Override public void stop() {
                redisConnection.close();
                redisClient.shutdown();
            }
        });
        log.info("redis.connected url={}", configuration.getRedis().getUrl());

        RedisCache cache = new RedisCache(redisConnection, environment.getObjectMapper());

        CounterHelper helper = new CounterHelper(jdbi, cache);

        environment.jersey().register(new RequestIdFilter());
        environment.jersey().register(new CounterResource(helper));
        environment.healthChecks().register("db", new DatabaseHealthCheck(jdbi));
    }

    private void runMigrations(DataSourceFactory db) {
        log.info("flyway.migrate.start url={}", db.getUrl());
        Flyway flyway = Flyway.configure()
                .dataSource(db.getUrl(), db.getUser(), db.getPassword())
                .locations("classpath:db/migration")
                .load();
        var result = flyway.migrate();
        log.info("flyway.migrate.done migrationsApplied={} targetSchemaVersion={}",
                result.migrationsExecuted, result.targetSchemaVersion);
    }
}

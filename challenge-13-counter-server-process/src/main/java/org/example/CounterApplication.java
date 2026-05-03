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
import org.example.cache.RedisCache;
import org.example.health.ShardClusterHealthCheck;
import org.example.web.RequestIdFilter;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
        List<DataSourceFactory> shardConfigs = configuration.getShardDbs();
        log.info("shard.cluster.init shardCount={}", shardConfigs.size());

        // Run migrations against EACH shard before serving traffic. Each shard
        // tracks its own flyway_schema_history independently. Sequential is
        // fine for a small cluster; production setups with many shards often
        // run migrations as a separate step in the deploy pipeline.
        for (int i = 0; i < shardConfigs.size(); i++) {
            runMigrations(i, shardConfigs.get(i));
        }

        // One JDBI per shard. Each has its own connection pool against its own
        // Postgres. Same DAO interfaces work against any of them — what differs
        // is which shard's pool issues the SQL.
        List<Jdbi> shardJdbis = new ArrayList<>();
        for (int i = 0; i < shardConfigs.size(); i++) {
            Jdbi jdbi = new JdbiFactory().build(environment, shardConfigs.get(i), "postgresql-shard-" + i);
            jdbi.installPlugin(new SqlObjectPlugin());
            shardJdbis.add(jdbi);
        }

        ShardRouter shardRouter = new ShardRouter(shardJdbis);

        // Redis client — unchanged from challenge 11/12. One Redis serves all
        // shards because the cache key already includes the counterId; whichever
        // counter you ask about, you hit the same Redis entry, regardless of
        // which Postgres shard owns it.
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

        CounterHelper helper = new CounterHelper(shardRouter, cache);

        environment.jersey().register(new RequestIdFilter());
        environment.jersey().register(new CounterResource(helper));
        // Health check probes every shard. If any shard is down, this instance
        // is unhealthy — losing a shard means losing 1/N of the keyspace.
        environment.healthChecks().register("db", new ShardClusterHealthCheck(shardRouter));
    }

    private void runMigrations(int shardIndex, DataSourceFactory db) {
        log.info("flyway.migrate.start shardIndex={} url={}", shardIndex, db.getUrl());
        Flyway flyway = Flyway.configure()
                .dataSource(db.getUrl(), db.getUser(), db.getPassword())
                .locations("classpath:db/migration")
                .load();
        var result = flyway.migrate();
        log.info("flyway.migrate.done shardIndex={} migrationsApplied={} targetSchemaVersion={}",
                shardIndex, result.migrationsExecuted, result.targetSchemaVersion);
    }
}

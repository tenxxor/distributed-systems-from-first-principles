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
        // Migrations run against the PRIMARY only. Replicas pick them up via
        // streaming WAL replication automatically (they're read-only and would
        // reject CREATE TABLE anyway).
        runMigrations(configuration.getPrimaryDb());

        // Two separate JDBI instances:
        //   - primaryJdbi: writes + Flyway-tracked operations
        //   - replicaJdbi: reads (load-balanced across replica hosts via JDBC)
        // Same DAO interfaces work against both — what differs is which connection
        // pool issues the SQL.
        Jdbi primaryJdbi = new JdbiFactory().build(environment, configuration.getPrimaryDb(), "postgresql-primary");
        primaryJdbi.installPlugin(new SqlObjectPlugin());

        Jdbi replicaJdbi = new JdbiFactory().build(environment, configuration.getReplicaDb(), "postgresql-replica");
        replicaJdbi.installPlugin(new SqlObjectPlugin());

        // Lettuce / Redis — unchanged from challenge 11.
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

        CounterHelper helper = new CounterHelper(primaryJdbi, replicaJdbi, cache);

        environment.jersey().register(new RequestIdFilter());
        environment.jersey().register(new CounterResource(helper));
        // Health check still runs against the primary — if the primary is down,
        // we want this instance marked unhealthy regardless of replica state.
        environment.healthChecks().register("db", new DatabaseHealthCheck(primaryJdbi));
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

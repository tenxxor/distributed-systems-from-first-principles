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
import org.example.tasks.TaskQueue;
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

    /**
     * Dispatcher entry point. The same JAR ships two roles:
     *   - "counter" (default): runs the Dropwizard HTTP server.
     *   - "worker": runs the long-lived task-processing loop.
     *
     * Compose decides which role each container plays via the `command:`
     * override. See docker-compose.yml.
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("worker")) {
            WorkerApplication.runWorker();
            return;
        }
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

        for (int i = 0; i < shardConfigs.size(); i++) {
            runMigrations(i, shardConfigs.get(i));
        }

        List<Jdbi> shardJdbis = new ArrayList<>();
        for (int i = 0; i < shardConfigs.size(); i++) {
            Jdbi jdbi = new JdbiFactory().build(environment, shardConfigs.get(i), "postgresql-shard-" + i);
            jdbi.installPlugin(new SqlObjectPlugin());
            shardJdbis.add(jdbi);
        }

        ShardRouter shardRouter = new ShardRouter(shardJdbis);

        // Two Redis clients — one for cache, one for queue. Independent
        // failure domains, independent persistence settings.
        StatefulRedisConnection<String, String> cacheConn =
                connectRedis(environment, configuration.getCacheRedis().getUrl(), "cache");
        StatefulRedisConnection<String, String> queueConn =
                connectRedis(environment, configuration.getQueueRedis().getUrl(), "queue");

        RedisCache cache = new RedisCache(cacheConn, environment.getObjectMapper());
        TaskQueue queue = new TaskQueue(queueConn, environment.getObjectMapper());

        CounterHelper helper = new CounterHelper(shardRouter, cache, queue);

        environment.jersey().register(new RequestIdFilter());
        environment.jersey().register(new CounterResource(helper));
        environment.healthChecks().register("db", new ShardClusterHealthCheck(shardRouter));
    }

    private StatefulRedisConnection<String, String> connectRedis(
            Environment environment, String url, String label) {
        RedisURI uri = RedisURI.create(url);
        uri.setTimeout(Duration.ofMillis(500));
        RedisClient client = RedisClient.create(uri);
        StatefulRedisConnection<String, String> conn = client.connect();
        environment.lifecycle().manage(new io.dropwizard.lifecycle.Managed() {
            @Override public void stop() {
                conn.close();
                client.shutdown();
            }
        });
        log.info("redis.connected role={} url={}", label, url);
        return conn;
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

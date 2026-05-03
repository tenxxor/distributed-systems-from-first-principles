package org.example;

import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.jdbi3.JdbiFactory;
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
        // Run schema migrations BEFORE wiring up JDBI/DAOs. Flyway takes a
        // database-level lock, so if three counter instances start in parallel
        // they'll race safely — exactly one runs the migration, the others
        // see it as already applied.
        runMigrations(configuration.getDatabase());

        Jdbi jdbi = new JdbiFactory().build(environment, configuration.getDatabase(), "postgresql");
        jdbi.installPlugin(new SqlObjectPlugin());

        CounterHelper helper = new CounterHelper(jdbi);

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

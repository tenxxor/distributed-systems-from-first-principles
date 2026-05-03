package org.example;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;
import io.dropwizard.db.DataSourceFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Config shape for the Dropwizard app.
 * Now includes a DataSourceFactory, which Dropwizard uses to build the JDBC connection pool
 * based on the `database:` section of config.yml.
 */
public class CounterConfiguration extends Configuration {

    @Valid
    @NotNull
    private DataSourceFactory database = new DataSourceFactory();

    @JsonProperty("database")
    public DataSourceFactory getDatabase() { return database; }

    @JsonProperty("database")
    public void setDatabase(DataSourceFactory database) { this.database = database; }
}

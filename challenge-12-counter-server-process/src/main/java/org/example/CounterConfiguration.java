package org.example;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;
import io.dropwizard.db.DataSourceFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Two database factories now: one for the primary (writes + migrations) and
 * one for the replica pool (reads). The replica URL is a multi-host JDBC URL
 * so the Postgres driver can round-robin reads across replicas natively.
 */
public class CounterConfiguration extends Configuration {

    @Valid
    @NotNull
    private DataSourceFactory primaryDb = new DataSourceFactory();

    @Valid
    @NotNull
    private DataSourceFactory replicaDb = new DataSourceFactory();

    @Valid
    @NotNull
    private RedisFactory redis = new RedisFactory();

    @JsonProperty("primaryDb")
    public DataSourceFactory getPrimaryDb() { return primaryDb; }

    @JsonProperty("primaryDb")
    public void setPrimaryDb(DataSourceFactory primaryDb) { this.primaryDb = primaryDb; }

    @JsonProperty("replicaDb")
    public DataSourceFactory getReplicaDb() { return replicaDb; }

    @JsonProperty("replicaDb")
    public void setReplicaDb(DataSourceFactory replicaDb) { this.replicaDb = replicaDb; }

    @JsonProperty("redis")
    public RedisFactory getRedis() { return redis; }

    @JsonProperty("redis")
    public void setRedis(RedisFactory redis) { this.redis = redis; }

    public static class RedisFactory {
        @NotNull
        private String url = "redis://redis:6379";

        @JsonProperty("url")
        public String getUrl() { return url; }

        @JsonProperty("url")
        public void setUrl(String url) { this.url = url; }
    }
}

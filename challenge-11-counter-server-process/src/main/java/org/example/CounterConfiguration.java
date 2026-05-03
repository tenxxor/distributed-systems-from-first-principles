package org.example;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;
import io.dropwizard.db.DataSourceFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public class CounterConfiguration extends Configuration {

    @Valid
    @NotNull
    private DataSourceFactory database = new DataSourceFactory();

    @Valid
    @NotNull
    private RedisFactory redis = new RedisFactory();

    @JsonProperty("database")
    public DataSourceFactory getDatabase() { return database; }

    @JsonProperty("database")
    public void setDatabase(DataSourceFactory database) { this.database = database; }

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

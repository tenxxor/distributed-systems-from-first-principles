package org.example;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;
import io.dropwizard.db.DataSourceFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for both the counter API and the worker, sharing this class.
 *
 * Two Redis factories now: cacheRedis (no persistence) and queueRedis (AOF on).
 * Splitting them gives us independent failure domains and per-instance config
 * (different persistence settings). See the README's notes for the trade-off
 * vs. sharing a single Redis.
 */
public class CounterConfiguration extends Configuration {

    @Valid
    @NotEmpty
    private List<DataSourceFactory> shardDbs = new ArrayList<>();

    @Valid
    @NotNull
    private RedisFactory cacheRedis = new RedisFactory();

    @Valid
    @NotNull
    private RedisFactory queueRedis = new RedisFactory();

    @JsonProperty("shardDbs")
    public List<DataSourceFactory> getShardDbs() { return shardDbs; }

    @JsonProperty("shardDbs")
    public void setShardDbs(List<DataSourceFactory> shardDbs) { this.shardDbs = shardDbs; }

    @JsonProperty("cacheRedis")
    public RedisFactory getCacheRedis() { return cacheRedis; }

    @JsonProperty("cacheRedis")
    public void setCacheRedis(RedisFactory cacheRedis) { this.cacheRedis = cacheRedis; }

    @JsonProperty("queueRedis")
    public RedisFactory getQueueRedis() { return queueRedis; }

    @JsonProperty("queueRedis")
    public void setQueueRedis(RedisFactory queueRedis) { this.queueRedis = queueRedis; }

    public static class RedisFactory {
        @NotNull
        private String url = "redis://redis-cache:6379";

        @JsonProperty("url")
        public String getUrl() { return url; }

        @JsonProperty("url")
        public void setUrl(String url) { this.url = url; }
    }
}

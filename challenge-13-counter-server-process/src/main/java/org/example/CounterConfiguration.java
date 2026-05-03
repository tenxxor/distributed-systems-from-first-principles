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
 * Sharded data layer: instead of one DB factory (or two — primary + replica
 * pool — like challenge 12), we now have a LIST of DB factories, one per
 * shard. Each shard is its own independent Postgres cluster.
 *
 * Reads and writes for a given counter both go to the *same* shard — sharding
 * partitions the keyspace, so the same shard owns the row regardless of
 * operation. Different counters can land on different shards, providing
 * parallelism for both reads and writes.
 *
 * No replicas in this challenge — pure sharding only. Production setups
 * combine sharding + replication (each shard has its own primary + replicas);
 * we leave that as a follow-up to keep the lesson focused.
 */
public class CounterConfiguration extends Configuration {

    @Valid
    @NotEmpty
    private List<DataSourceFactory> shardDbs = new ArrayList<>();

    @Valid
    @NotNull
    private RedisFactory redis = new RedisFactory();

    @JsonProperty("shardDbs")
    public List<DataSourceFactory> getShardDbs() { return shardDbs; }

    @JsonProperty("shardDbs")
    public void setShardDbs(List<DataSourceFactory> shardDbs) { this.shardDbs = shardDbs; }

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

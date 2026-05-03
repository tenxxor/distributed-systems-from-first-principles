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
 * Configuration shared by all roles (server, worker, notification consumer,
 * analytics-consumer, audit-consumer, trending-consumer).
 *
 * Challenge 15 keeps challenge 14's task queue (queueRedis) for notifications,
 * and adds Kafka for fan-out use cases (analytics, audit, trending). Both
 * infrastructures coexist; each does what it's good at.
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

    @Valid
    @NotNull
    private KafkaFactory kafka = new KafkaFactory();

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

    @JsonProperty("kafka")
    public KafkaFactory getKafka() { return kafka; }

    @JsonProperty("kafka")
    public void setKafka(KafkaFactory kafka) { this.kafka = kafka; }

    public static class RedisFactory {
        @NotNull
        private String url = "redis://redis-cache:6379";

        @JsonProperty("url")
        public String getUrl() { return url; }

        @JsonProperty("url")
        public void setUrl(String url) { this.url = url; }
    }

    public static class KafkaFactory {
        @NotNull
        private String bootstrapServers = "kafka:9092";

        @NotNull
        private String voteTopic = "vote-cast";

        @JsonProperty("bootstrapServers")
        public String getBootstrapServers() { return bootstrapServers; }

        @JsonProperty("bootstrapServers")
        public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }

        @JsonProperty("voteTopic")
        public String getVoteTopic() { return voteTopic; }

        @JsonProperty("voteTopic")
        public void setVoteTopic(String voteTopic) { this.voteTopic = voteTopic; }
    }
}

package com.causal.eventstore.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {
    private int partitions = 8;
    private SnapshotConfig snapshot = new SnapshotConfig();
    private BatchConfig batch = new BatchConfig();
    private ReplicationConfig replication = new ReplicationConfig();

    @Data
    public static class SnapshotConfig {
        private int threshold = 100;
        private int retention = 3;
    }

    @Data
    public static class BatchConfig {
        private int maxSize = 100;
    }

    @Data
    public static class ReplicationConfig {
        private String role = "LEADER";
        private String nodeId = "node-1";
        private String leaderAddress = "localhost:9090";
    }
}

package com.causal.eventstore.service;

import com.causal.eventstore.config.AppConfig;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class PartitionService {

    private final int partitionCount;

    public PartitionService(AppConfig appConfig) {
        this.partitionCount = appConfig.getPartitions();
    }

    public int getPartitionForAggregate(String aggregateId) {
        if (aggregateId == null) {
            return 0;
        }
        int hash = 0;
        byte[] bytes = aggregateId.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash = 31 * hash + (b & 0xff);
        }
        return Math.abs(hash) % partitionCount;
    }

    public int getPartitionCount() {
        return partitionCount;
    }
}

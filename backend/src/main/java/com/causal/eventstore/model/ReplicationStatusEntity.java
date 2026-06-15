package com.causal.eventstore.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "replication_status")
@IdClass(ReplicationStatusEntity.ReplicationStatusId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplicationStatusEntity {

    @Id
    @Column(name = "partition_id", nullable = false)
    private Integer partitionId;

    @Id
    @Column(name = "node_id", length = 255, nullable = false)
    private String nodeId;

    @Column(name = "last_applied_sequence", nullable = false)
    private Long lastAppliedSequence;

    @Column(name = "last_applied_event_id", length = 64)
    private String lastAppliedEventId;

    @Column(name = "last_heartbeat", nullable = false)
    private Instant lastHeartbeat;

    @Column(name = "lag_seconds")
    private Long lagSeconds;

    @PrePersist
    public void prePersist() {
        if (lastHeartbeat == null) lastHeartbeat = Instant.now();
        if (lastAppliedSequence == null) lastAppliedSequence = 0L;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReplicationStatusId implements Serializable {
        private Integer partitionId;
        private String nodeId;
    }
}

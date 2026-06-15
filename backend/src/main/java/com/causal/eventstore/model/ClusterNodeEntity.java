package com.causal.eventstore.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "cluster_nodes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterNodeEntity {

    public enum NodeRole {
        LEADER, FOLLOWER
    }

    public enum NodeStatus {
        UP, DOWN, DEGRADED
    }

    @Id
    @Column(name = "node_id", length = 255)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_role", length = 32, nullable = false)
    private NodeRole nodeRole;

    @Column(name = "host", nullable = false)
    private String host;

    @Column(name = "grpc_port", nullable = false)
    private Integer grpcPort;

    @Column(name = "http_port", nullable = false)
    private Integer httpPort;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private NodeStatus status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "last_heartbeat", nullable = false)
    private Instant lastHeartbeat;

    @PrePersist
    public void prePersist() {
        if (joinedAt == null) joinedAt = Instant.now();
        if (lastHeartbeat == null) lastHeartbeat = Instant.now();
        if (status == null) status = NodeStatus.UP;
    }
}

package com.causal.eventstore.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "snapshots", indexes = {
    @Index(name = "idx_snapshots_aggregate", columnList = "aggregate_id, last_sequence DESC")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "snapshot_id")
    private Long snapshotId;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_state", nullable = false, columnDefinition = "jsonb")
    private String snapshotState;

    @Column(name = "last_sequence", nullable = false)
    private Long lastSequence;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (sizeBytes == null && snapshotState != null) {
            sizeBytes = (long) snapshotState.getBytes().length;
        }
    }
}

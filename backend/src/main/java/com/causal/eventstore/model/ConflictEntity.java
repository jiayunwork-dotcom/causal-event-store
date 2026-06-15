package com.causal.eventstore.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "conflicts", indexes = {
    @Index(name = "idx_conflicts_aggregate", columnList = "aggregate_id"),
    @Index(name = "idx_conflicts_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConflictEntity {

    public enum ConflictStatus {
        OPEN, RESOLVED
    }

    public enum ResolutionType {
        KEEP_A, KEEP_B, CUSTOM
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "conflict_id")
    private Long conflictId;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_a_id", length = 64, nullable = false)
    private String eventAId;

    @Column(name = "event_b_id", length = 64, nullable = false)
    private String eventBId;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 32)
    private ResolutionType resolution;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ConflictStatus status;

    @PrePersist
    public void prePersist() {
        if (detectedAt == null) detectedAt = Instant.now();
        if (status == null) status = ConflictStatus.OPEN;
    }
}

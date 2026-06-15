package com.causal.eventstore.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "pending_projections", indexes = {
    @Index(name = "idx_pending_projections_projection", columnList = "projection_id, status"),
    @Index(name = "idx_pending_projections_event", columnList = "event_id"),
    @Index(name = "idx_pending_projections_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingProjectionEntity {

    public enum PendingStatus {
        PENDING, PROCESSING, PROCESSED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "projection_id", nullable = false)
    private String projectionId;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private PendingStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = PendingStatus.PENDING;
    }
}

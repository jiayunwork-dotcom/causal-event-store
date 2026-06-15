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
@Table(name = "projections")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectionEntity {

    public enum ProjectionStatus {
        RUNNING, STOPPED, REPLAYING, ERROR
    }

    @Id
    @Column(name = "projection_id", length = 255)
    private String projectionId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "event_type_pattern", nullable = false)
    private String eventTypePattern;

    @Column(name = "handler_logic", nullable = false, columnDefinition = "TEXT")
    private String handlerLogic;

    @Column(name = "target_table")
    private String targetTable;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "processed_vector", columnDefinition = "integer[]", nullable = false)
    private int[] processedVectorArray;

    @Column(name = "last_processed_event_id", length = 64)
    private String lastProcessedEventId;

    @Column(name = "processed_count", nullable = false)
    private Long processedCount;

    @Column(name = "last_processed_at")
    private Instant lastProcessedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ProjectionStatus status;

    @Transient
    private VectorClock processedVector;

    @PostLoad
    public void postLoad() {
        this.processedVector = VectorClock.fromIntArray(this.processedVectorArray);
    }

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (processedCount == null) processedCount = 0L;
        if (status == null) status = ProjectionStatus.RUNNING;
        if (processedVector != null) {
            this.processedVectorArray = processedVector.toIntArray();
        }
    }

    public void setProcessedVector(VectorClock processedVector) {
        this.processedVector = processedVector;
        if (processedVector != null) {
            this.processedVectorArray = processedVector.toIntArray();
        }
    }
}

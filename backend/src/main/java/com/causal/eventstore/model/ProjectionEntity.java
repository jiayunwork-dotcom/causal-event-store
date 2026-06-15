package com.causal.eventstore.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "projections")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectionEntity {

    public enum ProjectionStatus {
        RUNNING, STOPPED, REBUILDING, ERROR
    }

    public enum UpdateStrategy {
        REALTIME, BATCH
    }

    @Id
    @Column(name = "projection_id", length = 255)
    private String projectionId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "aggregate_type_pattern", nullable = false)
    private String aggregateTypePattern;

    @Column(name = "event_type_pattern", nullable = false)
    private String eventTypePattern;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "projection_expressions", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> projectionExpressions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_schema", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> outputSchema;

    @Column(name = "target_table")
    private String targetTable;

    @Enumerated(EnumType.STRING)
    @Column(name = "update_strategy", length = 32, nullable = false)
    private UpdateStrategy updateStrategy;

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

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ProjectionStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_at")
    private Instant errorAt;

    @Column(name = "rebuild_total_events")
    private Long rebuildTotalEvents;

    @Column(name = "rebuild_processed_events")
    private Long rebuildProcessedEvents;

    @Transient
    private VectorClock processedVector;

    @PostLoad
    public void postLoad() {
        this.processedVector = VectorClock.fromIntArray(this.processedVectorArray);
    }

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
        if (processedCount == null) processedCount = 0L;
        if (status == null) status = ProjectionStatus.RUNNING;
        if (updateStrategy == null) updateStrategy = UpdateStrategy.REALTIME;
        if (aggregateTypePattern == null) aggregateTypePattern = "*";
        if (eventTypePattern == null) eventTypePattern = "*";
        if (processedVector != null) {
            this.processedVectorArray = processedVector.toIntArray();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
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

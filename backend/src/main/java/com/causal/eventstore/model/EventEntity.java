package com.causal.eventstore.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "events", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"aggregate_id", "sequence_number"}),
    @UniqueConstraint(columnNames = {"partition_id", "partition_sequence_number"})
}, indexes = {
    @Index(name = "idx_events_aggregate", columnList = "aggregate_id, sequence_number"),
    @Index(name = "idx_events_partition", columnList = "partition_id, partition_sequence_number"),
    @Index(name = "idx_events_type", columnList = "event_type"),
    @Index(name = "idx_events_timestamp", columnList = "timestamp"),
    @Index(name = "idx_events_global_seq", columnList = "global_sequence")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventEntity {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "partition_id", nullable = false)
    private Integer partitionId;

    @Column(name = "sequence_number", nullable = false)
    private Long sequenceNumber;

    @Column(name = "partition_sequence_number", nullable = false)
    private Long partitionSequenceNumber;

    @Column(name = "global_sequence", insertable = false, updatable = false, columnDefinition = "BIGSERIAL")
    private Long globalSequence;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "vector_clock", columnDefinition = "integer[]", nullable = false)
    private int[] vectorClockArray;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "causal_dependencies", columnDefinition = "varchar[]", nullable = true)
    private String[] causalDependenciesArray;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Transient
    private VectorClock vectorClock;

    @Transient
    private List<String> causalDependencies;

    @PostLoad
    public void postLoad() {
        this.vectorClock = VectorClock.fromIntArray(this.vectorClockArray);
        this.causalDependencies = this.causalDependenciesArray != null
                ? List.of(this.causalDependenciesArray)
                : new ArrayList<>();
    }

    @PrePersist
    public void prePersist() {
        if (this.eventId == null) {
            this.eventId = UUID.randomUUID().toString().replace("-", "");
        }
        if (this.timestamp == null) {
            this.timestamp = Instant.now();
        }
        if (this.vectorClock != null) {
            this.vectorClockArray = this.vectorClock.toIntArray();
        }
        if (this.causalDependencies != null) {
            this.causalDependenciesArray = this.causalDependencies.toArray(new String[0]);
        }
    }

    public void setVectorClock(VectorClock vectorClock) {
        this.vectorClock = vectorClock;
        if (vectorClock != null) {
            this.vectorClockArray = vectorClock.toIntArray();
        }
    }

    public void setCausalDependencies(List<String> causalDependencies) {
        this.causalDependencies = causalDependencies;
        if (causalDependencies != null) {
            this.causalDependenciesArray = causalDependencies.toArray(new String[0]);
        }
    }
}

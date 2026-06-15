package com.causal.eventstore.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "aggregates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregateEntity {

    @Id
    @Column(name = "aggregate_id", length = 255)
    private String aggregateId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "current_sequence", nullable = false)
    private Long currentSequence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
        if (currentSequence == null) currentSequence = 0L;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}

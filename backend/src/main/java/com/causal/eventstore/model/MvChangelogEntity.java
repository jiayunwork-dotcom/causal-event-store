package com.causal.eventstore.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "mv_changelog")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MvChangelogEntity {

    public enum ChangeType {
        INSERT, UPDATE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "projection_id", nullable = false)
    private String projectionId;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", length = 16, nullable = false)
    private ChangeType changeType;

    @Column(name = "before_value", columnDefinition = "jsonb")
    private String beforeValue;

    @Column(name = "after_value", columnDefinition = "jsonb")
    private String afterValue;

    @Column(name = "trigger_event_id", length = 64)
    private String triggerEventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}

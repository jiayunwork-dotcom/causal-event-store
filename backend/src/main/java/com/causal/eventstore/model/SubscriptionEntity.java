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
@Table(name = "subscriptions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionEntity {

    @Id
    @Column(name = "subscription_id", length = 255)
    private String subscriptionId;

    @Column(name = "event_pattern", nullable = false)
    private String eventPattern;

    @Column(name = "consumer_id", nullable = false)
    private String consumerId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "cursor_vector", columnDefinition = "integer[]", nullable = false)
    private int[] cursorVectorArray;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_push_at")
    private Instant lastPushAt;

    @Transient
    private VectorClock cursorVector;

    @PostLoad
    public void postLoad() {
        this.cursorVector = VectorClock.fromIntArray(this.cursorVectorArray);
    }

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (cursorVector != null) {
            this.cursorVectorArray = cursorVector.toIntArray();
        }
    }

    public void setCursorVector(VectorClock cursorVector) {
        this.cursorVector = cursorVector;
        if (cursorVector != null) {
            this.cursorVectorArray = cursorVector.toIntArray();
        }
    }
}

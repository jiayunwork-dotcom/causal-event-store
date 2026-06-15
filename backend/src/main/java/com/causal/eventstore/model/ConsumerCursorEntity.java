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

@Entity
@Table(name = "consumer_cursors")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerCursorEntity {

    @Id
    @Column(name = "consumer_id", length = 255)
    private String consumerId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "cursor_vector", columnDefinition = "integer[]", nullable = false)
    private int[] cursorVectorArray;

    @Column(name = "last_event_id", length = 64)
    private String lastEventId;

    @Column(name = "acknowledged_at", nullable = false)
    private Instant acknowledgedAt;

    @Transient
    private VectorClock cursorVector;

    @PostLoad
    public void postLoad() {
        this.cursorVector = VectorClock.fromIntArray(this.cursorVectorArray);
    }

    @PrePersist
    public void prePersist() {
        if (acknowledgedAt == null) acknowledgedAt = Instant.now();
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

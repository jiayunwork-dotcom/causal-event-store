package com.causal.eventstore.dto;

import com.causal.eventstore.model.VectorClock;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventReadResponse {
    private String eventId;
    private String aggregateId;
    private String aggregateType;
    private String eventType;
    private String payload;
    private Integer partitionId;
    private Long sequenceNumber;
    private Long partitionSequenceNumber;
    private Long globalSequence;
    private VectorClock vectorClock;
    private List<String> causalDependencies;
    private Instant timestamp;
}

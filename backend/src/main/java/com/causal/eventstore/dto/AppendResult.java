package com.causal.eventstore.dto;

import com.causal.eventstore.model.VectorClock;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppendResult {
    private boolean success;
    private String message;
    private List<String> writtenEventIds;
    private VectorClock updatedVectorClock;
    private List<String> missingDependencies;
}

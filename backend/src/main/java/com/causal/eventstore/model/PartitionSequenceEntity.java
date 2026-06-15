package com.causal.eventstore.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "partition_sequence")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionSequenceEntity {

    @Id
    @Column(name = "partition_id")
    private Integer partitionId;

    @Column(name = "last_sequence", nullable = false)
    private Long lastSequence;
}

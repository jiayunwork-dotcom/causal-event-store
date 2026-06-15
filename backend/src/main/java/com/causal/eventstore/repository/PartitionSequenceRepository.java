package com.causal.eventstore.repository;

import com.causal.eventstore.model.PartitionSequenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface PartitionSequenceRepository extends JpaRepository<PartitionSequenceEntity, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PartitionSequenceEntity p WHERE p.partitionId = :partitionId")
    Optional<PartitionSequenceEntity> findByIdForUpdate(@Param("partitionId") Integer partitionId);
}

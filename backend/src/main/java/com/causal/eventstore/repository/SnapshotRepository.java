package com.causal.eventstore.repository;

import com.causal.eventstore.model.SnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SnapshotRepository extends JpaRepository<SnapshotEntity, Long> {

    List<SnapshotEntity> findByAggregateIdOrderByLastSequenceDesc(String aggregateId);

    Optional<SnapshotEntity> findTopByAggregateIdOrderByLastSequenceDesc(String aggregateId);

    @Query("SELECT s FROM SnapshotEntity s WHERE s.aggregateId = :aggregateId ORDER BY s.lastSequence DESC, s.snapshotId DESC")
    List<SnapshotEntity> findLatestSnapshots(@Param("aggregateId") String aggregateId);

    @Modifying
    @Query("DELETE FROM SnapshotEntity s WHERE s.aggregateId = :aggregateId AND s.snapshotId NOT IN " +
           "(SELECT s2.snapshotId FROM SnapshotEntity s2 WHERE s2.aggregateId = :aggregateId " +
           "ORDER BY s2.lastSequence DESC, s2.snapshotId DESC LIMIT :keepCount)")
    int deleteOldSnapshots(@Param("aggregateId") String aggregateId, @Param("keepCount") int keepCount);

    Long countByAggregateId(String aggregateId);
}

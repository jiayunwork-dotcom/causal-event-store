package com.causal.eventstore.repository;

import com.causal.eventstore.model.EventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<EventEntity, String> {

    List<EventEntity> findByAggregateIdOrderBySequenceNumberAsc(String aggregateId);

    List<EventEntity> findByAggregateIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            String aggregateId, Long sequenceNumber);

    Optional<EventEntity> findByAggregateIdAndSequenceNumber(String aggregateId, Long sequenceNumber);

    List<EventEntity> findByPartitionIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            Integer partitionId, Long sequenceNumber);

    Optional<EventEntity> findByPartitionIdAndSequenceNumber(Integer partitionId, Long sequenceNumber);

    @Query("SELECT e FROM EventEntity e WHERE e.eventType LIKE :pattern ORDER BY e.globalSequence ASC")
    List<EventEntity> findByEventTypeLike(@Param("pattern") String pattern);

    @Query("SELECT e FROM EventEntity e WHERE e.timestamp BETWEEN :start AND :end ORDER BY e.globalSequence ASC")
    List<EventEntity> findByTimestampBetween(@Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT e FROM EventEntity e WHERE e.eventId IN :ids")
    List<EventEntity> findByEventIdIn(@Param("ids") List<String> ids);

    @Query("SELECT e FROM EventEntity e WHERE e.globalSequence > :globalSeq ORDER BY e.globalSequence ASC")
    List<EventEntity> findByGlobalSequenceGreaterThanOrderByGlobalSequenceAsc(@Param("globalSeq") Long globalSeq);

    @Query("SELECT COUNT(e) FROM EventEntity e WHERE e.partitionId = :partitionId")
    Long countByPartitionId(@Param("partitionId") Integer partitionId);

    @Query("SELECT COUNT(e) FROM EventEntity e WHERE e.aggregateId = :aggregateId")
    Long countByAggregateId(@Param("aggregateId") String aggregateId);

    @Query("SELECT COUNT(DISTINCT e.aggregateId) FROM EventEntity e")
    Long countDistinctAggregates();

    @Query("SELECT e.partitionId, COUNT(e) FROM EventEntity e GROUP BY e.partitionId ORDER BY e.partitionId")
    List<Object[]> countEventsPerPartition();

    @Query("SELECT e FROM EventEntity e WHERE e.eventType = :eventType ORDER BY e.globalSequence ASC")
    List<EventEntity> findByEventType(@Param("eventType") String eventType);

    @Query("SELECT MAX(e.globalSequence) FROM EventEntity e")
    Optional<Long> findMaxGlobalSequence();
}

package com.causal.eventstore.repository;

import com.causal.eventstore.model.ConflictEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConflictRepository extends JpaRepository<ConflictEntity, Long> {

    List<ConflictEntity> findByStatus(ConflictEntity.ConflictStatus status);

    List<ConflictEntity> findByAggregateId(String aggregateId);

    List<ConflictEntity> findByAggregateIdAndStatus(String aggregateId, ConflictEntity.ConflictStatus status);

    Optional<ConflictEntity> findByAggregateIdAndEventAIdAndEventBId(
            String aggregateId, String eventAId, String eventBId);

    Optional<ConflictEntity> findByAggregateIdAndEventAIdAndEventBIdAndStatus(
            String aggregateId, String eventAId, String eventBId, ConflictEntity.ConflictStatus status);
}

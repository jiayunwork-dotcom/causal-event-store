package com.causal.eventstore.repository;

import com.causal.eventstore.model.MvChangelogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface MvChangelogRepository extends JpaRepository<MvChangelogEntity, Long> {

    Page<MvChangelogEntity> findByProjectionIdAndCreatedAtBetween(
            String projectionId, Instant startTime, Instant endTime, Pageable pageable);

    Page<MvChangelogEntity> findByProjectionIdAndAggregateIdAndCreatedAtBetween(
            String projectionId, String aggregateId, Instant startTime, Instant endTime, Pageable pageable);

    Page<MvChangelogEntity> findByProjectionId(String projectionId, Pageable pageable);

    Page<MvChangelogEntity> findByProjectionIdAndAggregateId(
            String projectionId, String aggregateId, Pageable pageable);

    long countByProjectionIdAndCreatedAtAfter(String projectionId, Instant since);
}

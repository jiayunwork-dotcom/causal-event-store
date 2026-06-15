package com.causal.eventstore.repository;

import com.causal.eventstore.model.PendingProjectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PendingProjectionRepository extends JpaRepository<PendingProjectionEntity, Long> {

    List<PendingProjectionEntity> findByProjectionIdAndStatusOrderByCreatedAtAsc(
            String projectionId, PendingProjectionEntity.PendingStatus status);

    List<PendingProjectionEntity> findByProjectionIdAndStatusInOrderByCreatedAtAsc(
            String projectionId, List<PendingProjectionEntity.PendingStatus> statuses);

    long countByProjectionIdAndStatus(String projectionId, PendingProjectionEntity.PendingStatus status);

    @Modifying
    @Query("DELETE FROM PendingProjectionEntity p WHERE p.createdAt < :cutoff AND p.status = :status")
    int deleteOldByStatus(@Param("cutoff") Instant cutoff, @Param("status") PendingProjectionEntity.PendingStatus status);

    @Modifying
    @Query("DELETE FROM PendingProjectionEntity p WHERE p.projectionId = :projectionId")
    int deleteByProjectionId(@Param("projectionId") String projectionId);

    boolean existsByProjectionIdAndEventIdAndStatus(String projectionId, String eventId, PendingProjectionEntity.PendingStatus status);
}

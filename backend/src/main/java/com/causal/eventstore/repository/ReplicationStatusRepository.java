package com.causal.eventstore.repository;

import com.causal.eventstore.model.ReplicationStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReplicationStatusRepository extends JpaRepository<ReplicationStatusEntity, Long> {

    List<ReplicationStatusEntity> findByNodeId(String nodeId);

    Optional<ReplicationStatusEntity> findByPartitionIdAndNodeId(Integer partitionId, String nodeId);

    List<ReplicationStatusEntity> findByPartitionId(Integer partitionId);

    @Query("SELECT r FROM ReplicationStatusEntity r WHERE r.nodeId = :nodeId ORDER BY r.partitionId")
    List<ReplicationStatusEntity> findByNodeIdOrderByPartitionId(@Param("nodeId") String nodeId);
}

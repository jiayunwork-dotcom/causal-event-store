package com.causal.eventstore.repository;

import com.causal.eventstore.model.ClusterNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClusterNodeRepository extends JpaRepository<ClusterNodeEntity, String> {

    List<ClusterNodeEntity> findByNodeRole(ClusterNodeEntity.NodeRole role);

    Optional<ClusterNodeEntity> findByNodeId(String nodeId);

    List<ClusterNodeEntity> findByStatus(ClusterNodeEntity.NodeStatus status);
}

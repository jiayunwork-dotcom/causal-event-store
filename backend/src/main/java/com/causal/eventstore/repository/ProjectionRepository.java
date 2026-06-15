package com.causal.eventstore.repository;

import com.causal.eventstore.model.ProjectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectionRepository extends JpaRepository<ProjectionEntity, String> {

    List<ProjectionEntity> findByStatus(ProjectionEntity.ProjectionStatus status);

    List<ProjectionEntity> findByStatusIn(List<ProjectionEntity.ProjectionStatus> statuses);

    List<ProjectionEntity> findByUpdateStrategy(ProjectionEntity.UpdateStrategy updateStrategy);
}

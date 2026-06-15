package com.causal.eventstore.repository;

import com.causal.eventstore.model.ProjectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectionRepository extends JpaRepository<ProjectionEntity, String> {

    List<ProjectionEntity> findByStatus(ProjectionEntity.ProjectionStatus status);

    List<ProjectionEntity> findByStatusIn(List<ProjectionEntity.ProjectionStatus> statuses);

    List<ProjectionEntity> findByUpdateStrategy(ProjectionEntity.UpdateStrategy updateStrategy);

    List<ProjectionEntity> findByUpstreamProjectionId(String upstreamProjectionId);

    List<ProjectionEntity> findByBaseProjectionIdOrderByVersionAsc(String baseProjectionId);

    Optional<ProjectionEntity> findByBaseProjectionIdAndVersion(String baseProjectionId, Integer version);

    Optional<ProjectionEntity> findByBaseProjectionIdAndVersionStatus(String baseProjectionId, ProjectionEntity.VersionStatus versionStatus);

    List<ProjectionEntity> findByVersionStatusAndArchivedAtBefore(ProjectionEntity.VersionStatus versionStatus, java.time.Instant before);

    List<ProjectionEntity> findByBaseProjectionIdAndVersionStatusIn(String baseProjectionId, List<ProjectionEntity.VersionStatus> statuses);
}

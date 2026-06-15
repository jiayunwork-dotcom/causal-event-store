package com.causal.eventstore.service;

import com.causal.eventstore.config.AppConfig;
import com.causal.eventstore.model.EventEntity;
import com.causal.eventstore.model.ProjectionEntity;
import com.causal.eventstore.model.VectorClock;
import com.causal.eventstore.repository.EventRepository;
import com.causal.eventstore.repository.ProjectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ProjectionService {

    private final ProjectionRepository projectionRepository;
    private final EventRepository eventRepository;
    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    public ProjectionService(ProjectionRepository projectionRepository,
                             EventRepository eventRepository,
                             AppConfig appConfig,
                             ObjectMapper objectMapper,
                             DataSource dataSource) {
        this.projectionRepository = projectionRepository;
        this.eventRepository = eventRepository;
        this.appConfig = appConfig;
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    @Transactional
    public ProjectionEntity createProjection(String id, String name, String description,
                                             String eventTypePattern, String handlerLogic,
                                             String targetTable) {
        ProjectionEntity p = ProjectionEntity.builder()
                .projectionId(id != null ? id : "proj-" + UUID.randomUUID().toString().substring(0, 8))
                .name(name)
                .description(description)
                .eventTypePattern(eventTypePattern)
                .handlerLogic(handlerLogic)
                .targetTable(targetTable)
                .processedCount(0L)
                .status(ProjectionEntity.ProjectionStatus.RUNNING)
                .createdAt(Instant.now())
                .build();
        p.setProcessedVector(new VectorClock(appConfig.getPartitions()));

        if (targetTable != null && !targetTable.isEmpty()) {
            createProjectionTable(targetTable);
        }

        return projectionRepository.save(p);
    }

    private void createProjectionTable(String tableName) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                    "id BIGSERIAL PRIMARY KEY," +
                    "event_id VARCHAR(64) UNIQUE," +
                    "aggregate_id VARCHAR(255)," +
                    "event_type VARCHAR(255)," +
                    "payload JSONB," +
                    "processed_at TIMESTAMPTZ DEFAULT NOW()" +
                    ")";
            stmt.execute(sql);
        } catch (Exception e) {
            log.warn("Failed to create projection table {}", tableName, e);
        }
    }

    public List<ProjectionEntity> listProjections() {
        return projectionRepository.findAll();
    }

    public Optional<ProjectionEntity> getProjection(String id) {
        return projectionRepository.findById(id);
    }

    @Transactional
    public void triggerReplay(String projectionId) {
        ProjectionEntity p = projectionRepository.findById(projectionId)
                .orElseThrow(() -> new IllegalArgumentException("Projection not found: " + projectionId));

        if (p.getTargetTable() != null) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("TRUNCATE TABLE " + p.getTargetTable());
            } catch (Exception e) {
                log.warn("Failed to truncate projection table", e);
            }
        }

        p.setProcessedVector(new VectorClock(appConfig.getPartitions()));
        p.setLastProcessedEventId(null);
        p.setProcessedCount(0L);
        p.setStatus(ProjectionEntity.ProjectionStatus.REPLAYING);
        projectionRepository.save(p);
    }

    @Async
    @Scheduled(fixedDelay = 2000)
    public void processProjections() {
        List<ProjectionEntity> projections = projectionRepository.findByStatus(ProjectionEntity.ProjectionStatus.RUNNING);
        projections.addAll(projectionRepository.findByStatus(ProjectionEntity.ProjectionStatus.REPLAYING));

        for (ProjectionEntity p : projections) {
            try {
                processProjection(p);
            } catch (Exception e) {
                log.warn("Projection processing failed: {}", p.getProjectionId(), e);
            }
        }
    }

    @Transactional
    public void processProjection(ProjectionEntity p) {
        VectorClock currentVc = p.getProcessedVector() != null ? p.getProcessedVector()
                : new VectorClock(appConfig.getPartitions());
        String lastEventId = p.getLastProcessedEventId();

        Long startGs = 0L;
        if (lastEventId != null) {
            Optional<EventEntity> last = eventRepository.findById(lastEventId);
            if (last.isPresent() && last.get().getGlobalSequence() != null) {
                startGs = last.get().getGlobalSequence();
            }
        }

        List<EventEntity> batch = eventRepository
                .findByGlobalSequenceGreaterThanOrderByGlobalSequenceAsc(startGs);
        if (batch.size() > 100) {
            batch = batch.subList(0, 100);
        }

        Pattern pattern = compilePattern(p.getEventTypePattern());

        long processed = 0;
        VectorClock mergedVc = currentVc.clone();
        String lastProcessedId = lastEventId;

        for (EventEntity event : batch) {
            if (!matchesPattern(event.getEventType(), pattern)) continue;
            if (event.getVectorClock() != null && event.getVectorClock().strictlyGreaterThan(mergedVc)) {
                mergedVc = mergedVc.merge(event.getVectorClock());
            }

            if (p.getTargetTable() != null) {
                insertProjectionRow(p.getTargetTable(), event);
            }

            processed++;
            lastProcessedId = event.getEventId();
        }

        if (processed > 0) {
            p.setProcessedVector(mergedVc);
            p.setLastProcessedEventId(lastProcessedId);
            p.setProcessedCount(p.getProcessedCount() + processed);
            p.setLastProcessedAt(Instant.now());
            if (p.getStatus() == ProjectionEntity.ProjectionStatus.REPLAYING && batch.size() < 100) {
                p.setStatus(ProjectionEntity.ProjectionStatus.RUNNING);
            }
            projectionRepository.save(p);
        }
    }

    private Pattern compilePattern(String pattern) {
        if (pattern == null || pattern.isEmpty() || "*".equals(pattern)) return null;
        String regex = pattern.replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return Pattern.compile(regex);
    }

    private boolean matchesPattern(String eventType, Pattern pattern) {
        if (pattern == null) return true;
        return pattern.matcher(eventType).matches();
    }

    private void insertProjectionRow(String tableName, EventEntity event) {
        String sql = "INSERT INTO " + tableName + " (event_id, aggregate_id, event_type, payload, processed_at) VALUES (?, ?, ?, ?::jsonb, ?) ON CONFLICT DO NOTHING";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, event.getEventId());
            ps.setString(2, event.getAggregateId());
            ps.setString(3, event.getEventType());
            ps.setString(4, event.getPayload());
            ps.setTimestamp(5, java.sql.Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Failed to insert into projection table", e);
        }
    }

    @Transactional
    public void deleteProjection(String projectionId) {
        ProjectionEntity p = projectionRepository.findById(projectionId).orElse(null);
        if (p != null && p.getTargetTable() != null) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + p.getTargetTable());
            } catch (Exception e) {
                log.warn("Failed to drop projection table", e);
            }
        }
        projectionRepository.deleteById(projectionId);
    }
}

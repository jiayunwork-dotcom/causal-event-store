package com.causal.eventstore.service;

import com.causal.eventstore.config.AppConfig;
import com.causal.eventstore.model.*;
import com.causal.eventstore.repository.EventRepository;
import com.causal.eventstore.repository.MvChangelogRepository;
import com.causal.eventstore.repository.PendingProjectionRepository;
import com.causal.eventstore.repository.ProjectionRepository;
import com.causal.eventstore.util.JsonPathUtil;
import com.causal.eventstore.util.ProjectionSchemaUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ProjectionService {

    private final ProjectionRepository projectionRepository;
    private final EventRepository eventRepository;
    private final PendingProjectionRepository pendingProjectionRepository;
    private final MvChangelogRepository mvChangelogRepository;
    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    private final ProjectionMetricsTracker metricsTracker;

    private static final int BATCH_SIZE = 100;
    private static final int PENDING_BATCH_SIZE = 50;
    private static final int MAX_CHAIN_DEPTH = 3;
    private static final long ARCHIVE_RETENTION_DAYS = 30;

    public ProjectionService(ProjectionRepository projectionRepository,
                             EventRepository eventRepository,
                             PendingProjectionRepository pendingProjectionRepository,
                             MvChangelogRepository mvChangelogRepository,
                             AppConfig appConfig,
                             ObjectMapper objectMapper,
                             DataSource dataSource) {
        this.projectionRepository = projectionRepository;
        this.eventRepository = eventRepository;
        this.pendingProjectionRepository = pendingProjectionRepository;
        this.mvChangelogRepository = mvChangelogRepository;
        this.appConfig = appConfig;
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
        this.metricsTracker = new ProjectionMetricsTracker();
    }

    @Transactional
    public ProjectionEntity createProjection(String projectionId, String name, String description,
                                             String aggregateTypePattern, String eventTypePattern,
                                             Map<String, String> projectionExpressions,
                                             Map<String, Object> outputSchema,
                                             ProjectionEntity.UpdateStrategy updateStrategy,
                                             String upstreamProjectionId) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Projection name is required");
        }

        if (projectionExpressions == null || projectionExpressions.isEmpty()) {
            throw new IllegalArgumentException("Projection expressions are required");
        }

        for (Map.Entry<String, String> entry : projectionExpressions.entrySet()) {
            JsonPathUtil.ValidationResult validation = JsonPathUtil.validateJsonPath(entry.getValue());
            if (!validation.isValid()) {
                throw new IllegalArgumentException(
                    "Invalid JSON Path expression for field '" + entry.getKey() + "': "
                    + validation.getErrorMessage()
                    + (validation.getErrorPosition() >= 0 ? " (position " + validation.getErrorPosition() + ")" : "")
                );
            }
        }

        String id = projectionId != null && !projectionId.trim().isEmpty()
            ? projectionId
            : "proj-" + UUID.randomUUID().toString().substring(0, 8);

        if (upstreamProjectionId != null && !upstreamProjectionId.trim().isEmpty()) {
            validateChainDepth(upstreamProjectionId);
            ProjectionEntity upstream = resolveActiveProjection(upstreamProjectionId);
            if (upstream == null) {
                throw new IllegalArgumentException("Upstream projection not found: " + upstreamProjectionId);
            }
            if (upstream.getStatus() == ProjectionEntity.ProjectionStatus.ERROR
                || upstream.getStatus() == ProjectionEntity.ProjectionStatus.STOPPED) {
                throw new IllegalArgumentException("Upstream projection is not available: " + upstreamProjectionId);
            }
        } else {
            upstreamProjectionId = null;
        }

        String targetTable = "mv_" + id.toLowerCase().replaceAll("[^a-z0-9_]", "_");

        createMaterializedViewTable(targetTable, outputSchema);

        ProjectionEntity p = ProjectionEntity.builder()
                .projectionId(id)
                .name(name)
                .description(description)
                .aggregateTypePattern(aggregateTypePattern != null ? aggregateTypePattern : "*")
                .eventTypePattern(eventTypePattern != null ? eventTypePattern : "*")
                .projectionExpressions(projectionExpressions)
                .outputSchema(outputSchema != null ? outputSchema : Map.of())
                .targetTable(targetTable)
                .updateStrategy(updateStrategy != null ? updateStrategy : ProjectionEntity.UpdateStrategy.REALTIME)
                .processedCount(0L)
                .rebuildTotalEvents(0L)
                .rebuildProcessedEvents(0L)
                .status(upstreamProjectionId != null ? ProjectionEntity.ProjectionStatus.RUNNING : ProjectionEntity.ProjectionStatus.RUNNING)
                .upstreamProjectionId(upstreamProjectionId)
                .baseProjectionId(id)
                .version(1)
                .versionStatus(ProjectionEntity.VersionStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        p.setProcessedVector(new VectorClock(appConfig.getPartitions()));

        return projectionRepository.save(p);
    }

    private void validateChainDepth(String upstreamProjectionId) {
        int depth = 1;
        String current = upstreamProjectionId;
        Set<String> visited = new HashSet<>();
        while (current != null) {
            if (visited.contains(current)) {
                throw new IllegalArgumentException("Circular dependency detected in projection chain");
            }
            visited.add(current);
            if (depth > MAX_CHAIN_DEPTH) {
                throw new IllegalArgumentException("Chain depth exceeds maximum of " + MAX_CHAIN_DEPTH + " layers");
            }
            ProjectionEntity upstream = resolveActiveProjection(current);
            if (upstream == null) break;
            current = upstream.getUpstreamProjectionId();
            if (current != null) depth++;
        }
    }

    public ProjectionEntity resolveActiveProjection(String baseOrProjectionId) {
        Optional<ProjectionEntity> direct = projectionRepository.findById(baseOrProjectionId);
        if (direct.isPresent()) {
            ProjectionEntity p = direct.get();
            if (p.getVersionStatus() == ProjectionEntity.VersionStatus.ACTIVE) {
                return p;
            }
        }
        Optional<ProjectionEntity> active = projectionRepository
            .findByBaseProjectionIdAndVersionStatus(baseOrProjectionId, ProjectionEntity.VersionStatus.ACTIVE);
        return active.orElse(direct.orElse(null));
    }

    @Transactional
    public ProjectionEntity updateProjection(String projectionId, String name, String description,
                                             String aggregateTypePattern, String eventTypePattern,
                                             Map<String, String> projectionExpressions,
                                             Map<String, Object> outputSchema,
                                             ProjectionEntity.UpdateStrategy updateStrategy) {
        ProjectionEntity p = projectionRepository.findById(projectionId)
                .orElseThrow(() -> new IllegalArgumentException("Projection not found: " + projectionId));

        if (name != null) p.setName(name);
        if (description != null) p.setDescription(description);
        if (aggregateTypePattern != null) p.setAggregateTypePattern(aggregateTypePattern);
        if (eventTypePattern != null) p.setEventTypePattern(eventTypePattern);
        if (updateStrategy != null) p.setUpdateStrategy(updateStrategy);

        if (projectionExpressions != null && !projectionExpressions.isEmpty()) {
            for (Map.Entry<String, String> entry : projectionExpressions.entrySet()) {
                JsonPathUtil.ValidationResult validation = JsonPathUtil.validateJsonPath(entry.getValue());
                if (!validation.isValid()) {
                    throw new IllegalArgumentException(
                        "Invalid JSON Path for field '" + entry.getKey() + "': "
                        + validation.getErrorMessage()
                    );
                }
            }
            p.setProjectionExpressions(projectionExpressions);
        }

        if (outputSchema != null) {
            p.setOutputSchema(outputSchema);
        }

        return projectionRepository.save(p);
    }

    public List<ProjectionEntity> listProjections() {
        return projectionRepository.findAll();
    }

    public Optional<ProjectionEntity> getProjection(String id) {
        return projectionRepository.findById(id);
    }

    @Transactional
    public void deleteProjection(String projectionId) {
        ProjectionEntity p = projectionRepository.findById(projectionId).orElse(null);
        if (p != null && p.getTargetTable() != null) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + p.getTargetTable());
            } catch (Exception e) {
                log.warn("Failed to drop materialized view table", e);
            }
        }
        pendingProjectionRepository.deleteByProjectionId(projectionId);
        projectionRepository.deleteById(projectionId);
    }

    @Transactional
    public void triggerRebuild(String projectionId) {
        ProjectionEntity p = projectionRepository.findById(projectionId)
                .orElseThrow(() -> new IllegalArgumentException("Projection not found: " + projectionId));

        long totalEvents = countMatchingEvents(p);

        p.setStatus(ProjectionEntity.ProjectionStatus.REBUILDING);
        p.setRebuildTotalEvents(totalEvents);
        p.setRebuildProcessedEvents(0L);
        p.setProcessedVector(new VectorClock(appConfig.getPartitions()));
        p.setLastProcessedEventId(null);
        p.setProcessedCount(0L);
        p.setLastProcessedAt(null);
        p.setErrorMessage(null);
        p.setErrorAt(null);
        projectionRepository.save(p);
        projectionRepository.flush();
    }

    private long countMatchingEvents(ProjectionEntity projection) {
        try {
            return eventRepository.count();
        } catch (Exception e) {
            return 0;
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void processPendingProjections() {
        List<ProjectionEntity> projections = projectionRepository.findByStatusIn(
            Arrays.asList(ProjectionEntity.ProjectionStatus.RUNNING, ProjectionEntity.ProjectionStatus.REBUILDING)
        );

        for (ProjectionEntity p : projections) {
            try {
                if (p.getUpstreamProjectionId() != null) {
                    continue;
                }
                if (p.getStatus() == ProjectionEntity.ProjectionStatus.REBUILDING) {
                    processRebuild(p);
                } else {
                    processRealtimeUpdates(p);
                }
            } catch (Exception e) {
                log.error("Error processing projection: {}", p.getProjectionId(), e);
                markProjectionError(p, e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void processChainedProjections() {
        List<ProjectionEntity> chained = projectionRepository.findByStatusIn(
            Arrays.asList(ProjectionEntity.ProjectionStatus.RUNNING)
        ).stream()
         .filter(p -> p.getUpstreamProjectionId() != null)
         .sorted(Comparator.comparing(ProjectionEntity::getCreatedAt))
         .collect(Collectors.toList());

        for (ProjectionEntity p : chained) {
            try {
                processChainedUpdate(p);
            } catch (Exception e) {
                log.error("Error processing chained projection: {}", p.getProjectionId(), e);
                metricsTracker.recordProcessing(p.getProjectionId(), 0, false);
                markProjectionError(p, e.getMessage());
            }
        }
    }

    @Transactional
    protected void processChainedUpdate(ProjectionEntity projection) {
        ProjectionEntity upstream = resolveActiveProjection(projection.getUpstreamProjectionId());
        if (upstream == null) {
            return;
        }

        List<PendingProjectionEntity> pending = pendingProjectionRepository
            .findByProjectionIdAndStatusOrderByCreatedAtAsc(
                projection.getProjectionId(), PendingProjectionEntity.PendingStatus.PENDING
            );

        if (pending.isEmpty()) return;

        if (pending.size() > PENDING_BATCH_SIZE) {
            pending = pending.subList(0, PENDING_BATCH_SIZE);
        }

        int processed = 0;
        for (PendingProjectionEntity pp : pending) {
            pp.setStatus(PendingProjectionEntity.PendingStatus.PROCESSING);
            pendingProjectionRepository.save(pp);
        }
        pendingProjectionRepository.flush();

        for (PendingProjectionEntity pp : pending) {
            long startMs = System.currentTimeMillis();
            try {
                applyUpstreamRowToMaterializedView(projection, upstream, pp.getAggregateId());
                pp.setStatus(PendingProjectionEntity.PendingStatus.PROCESSED);
                pp.setProcessedAt(Instant.now());
                long latency = System.currentTimeMillis() - startMs;
                metricsTracker.recordProcessing(projection.getProjectionId(), latency, true);
                processed++;
            } catch (Exception e) {
                log.warn("Failed to process chained projection {} for aggregate {}: {}",
                    pp.getProjectionId(), pp.getAggregateId(), e.getMessage());
                pp.setStatus(PendingProjectionEntity.PendingStatus.FAILED);
                pp.setErrorMessage(e.getMessage());
                metricsTracker.recordProcessing(projection.getProjectionId(), 0, false);
            }
            pendingProjectionRepository.save(pp);
        }

        if (processed > 0) {
            projection.setProcessedCount(projection.getProcessedCount() + processed);
            projection.setLastProcessedAt(Instant.now());
            projectionRepository.save(projection);
        }
    }

    private void applyUpstreamRowToMaterializedView(ProjectionEntity projection, ProjectionEntity upstream, String aggregateId) {
        Map<String, Object> upstreamRow = readUpstreamMvRow(upstream.getTargetTable(), aggregateId);
        if (upstreamRow == null) return;

        Map<String, Object> projectedValues = new LinkedHashMap<>();
        Map<String, Object> schemaFields = ProjectionSchemaUtil.getFieldsFromSchema(projection.getOutputSchema());

        for (Map.Entry<String, String> exprEntry : projection.getProjectionExpressions().entrySet()) {
            String fieldName = exprEntry.getKey();
            String jsonPath = exprEntry.getValue();
            Object value = extractFromRow(upstreamRow, jsonPath);
            projectedValues.put(fieldName, value);
        }

        Map<String, Object> finalValues = new LinkedHashMap<>();
        for (String fieldName : schemaFields.keySet()) {
            finalValues.put(fieldName, projectedValues.getOrDefault(fieldName, null));
        }

        upsertMaterializedViewFromMap(projection, aggregateId, finalValues, null);
    }

    private Object extractFromRow(Map<String, Object> row, String jsonPath) {
        if (jsonPath == null || jsonPath.equals("$")) return row;
        if (jsonPath.startsWith("$.")) {
            String fieldPath = jsonPath.substring(2);
            String[] parts = fieldPath.split("\\.");
            Object current = row;
            for (String part : parts) {
                if (current instanceof Map) {
                    current = ((Map<String, Object>) current).get(part);
                } else {
                    return null;
                }
            }
            return current;
        }
        return row.get(jsonPath);
    }

    private Map<String, Object> readUpstreamMvRow(String targetTable, String aggregateId) {
        String sql = "SELECT * FROM " + targetTable + " WHERE aggregate_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, aggregateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnName(i), rs.getObject(i));
                    }
                    return row;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read upstream MV row for aggregate {}", aggregateId, e);
        }
        return null;
    }

    private void upsertMaterializedViewFromMap(ProjectionEntity projection, String aggregateId,
                                                Map<String, Object> finalValues, String triggerEventId) {
        try (Connection conn = dataSource.getConnection()) {
            Map<String, Object> beforeValues = readMvRow(projection.getTargetTable(), aggregateId);
            boolean isInsert = (beforeValues == null);

            StringBuilder sql = new StringBuilder();
            sql.append("INSERT INTO ").append(projection.getTargetTable());
            sql.append(" (aggregate_id, aggregate_type, last_event_id, last_event_type, updated_at");

            for (String field : finalValues.keySet()) {
                sql.append(", ").append(escapeIdentifier(field));
            }
            sql.append(") VALUES (?, ?, ?, ?, ?");

            for (int i = 0; i < finalValues.size(); i++) {
                sql.append(", ?");
            }
            sql.append(") ON CONFLICT (aggregate_id) DO UPDATE SET ");
            sql.append("aggregate_type = EXCLUDED.aggregate_type,");
            sql.append("last_event_id = EXCLUDED.last_event_id,");
            sql.append("last_event_type = EXCLUDED.last_event_type,");
            sql.append("updated_at = EXCLUDED.updated_at");

            for (String field : finalValues.keySet()) {
                sql.append(", ").append(escapeIdentifier(field)).append(" = EXCLUDED.").append(escapeIdentifier(field));
            }

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                ps.setString(idx++, aggregateId);
                ps.setString(idx++, "");
                ps.setString(idx++, triggerEventId != null ? triggerEventId : "");
                ps.setString(idx++, "");
                ps.setTimestamp(idx++, Timestamp.from(Instant.now()));

                for (Map.Entry<String, Object> entry : finalValues.entrySet()) {
                    setPreparedStatementValue(ps, idx++, entry.getValue());
                }

                ps.executeUpdate();
            }

            Map<String, Object> afterValues = readMvRow(projection.getTargetTable(), aggregateId);
            recordChangelogAsync(projection.getProjectionId(), aggregateId,
                isInsert ? MvChangelogEntity.ChangeType.INSERT : MvChangelogEntity.ChangeType.UPDATE,
                beforeValues, afterValues, triggerEventId);

            triggerDownstreamProjections(projection.getBaseProjectionId(), aggregateId);

        } catch (Exception e) {
            throw new RuntimeException("Failed to update materialized view", e);
        }
    }

    @Transactional
    protected void processRealtimeUpdates(ProjectionEntity projection) {
        List<PendingProjectionEntity> pending = pendingProjectionRepository
            .findByProjectionIdAndStatusOrderByCreatedAtAsc(
                projection.getProjectionId(), PendingProjectionEntity.PendingStatus.PENDING
            );

        if (pending.isEmpty()) return;

        if (pending.size() > PENDING_BATCH_SIZE) {
            pending = pending.subList(0, PENDING_BATCH_SIZE);
        }

        int processed = 0;
        for (PendingProjectionEntity pp : pending) {
            pp.setStatus(PendingProjectionEntity.PendingStatus.PROCESSING);
            pendingProjectionRepository.save(pp);
        }
        pendingProjectionRepository.flush();

        for (PendingProjectionEntity pp : pending) {
            long startMs = System.currentTimeMillis();
            try {
                Optional<EventEntity> eventOpt = eventRepository.findById(pp.getEventId());
                if (eventOpt.isPresent()) {
                    applyEventToMaterializedView(projection, eventOpt.get());
                    pp.setStatus(PendingProjectionEntity.PendingStatus.PROCESSED);
                    pp.setProcessedAt(Instant.now());
                    long latency = System.currentTimeMillis() - startMs;
                    metricsTracker.recordProcessing(projection.getProjectionId(), latency, true);
                    processed++;
                } else {
                    pp.setStatus(PendingProjectionEntity.PendingStatus.FAILED);
                    pp.setErrorMessage("Event not found");
                    metricsTracker.recordProcessing(projection.getProjectionId(), 0, false);
                }
            } catch (Exception e) {
                log.warn("Failed to process pending projection {} for event {}: {}",
                    pp.getProjectionId(), pp.getEventId(), e.getMessage());
                pp.setStatus(PendingProjectionEntity.PendingStatus.FAILED);
                pp.setErrorMessage(e.getMessage());
                metricsTracker.recordProcessing(projection.getProjectionId(), 0, false);
            }
            pendingProjectionRepository.save(pp);
        }

        if (processed > 0) {
            projection.setProcessedCount(projection.getProcessedCount() + processed);
            projection.setLastProcessedAt(Instant.now());
            projectionRepository.save(projection);
        }
    }

    @Transactional
    protected void processRebuild(ProjectionEntity projection) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("TRUNCATE TABLE " + projection.getTargetTable());
                }

                VectorClock startVc = new VectorClock(appConfig.getPartitions());
                long startGs = 0L;
                long processed = 0;

                while (true) {
                    List<EventEntity> batch = eventRepository
                        .findByGlobalSequenceGreaterThanOrderByGlobalSequenceAsc(startGs);

                    if (batch.isEmpty()) break;
                    if (batch.size() > BATCH_SIZE) {
                        batch = batch.subList(0, BATCH_SIZE);
                    }

                    VectorClock mergedVc = startVc.clone();
                    String lastEventId = null;
                    long lastGs = startGs;

                    for (EventEntity event : batch) {
                        if (!matchesProjection(projection, event)) continue;

                        insertMaterializedViewRow(conn, projection, event);

                        if (event.getVectorClock() != null && event.getVectorClock().strictlyGreaterThan(mergedVc)) {
                            mergedVc = mergedVc.merge(event.getVectorClock());
                        }
                        lastEventId = event.getEventId();
                        if (event.getGlobalSequence() != null) {
                            lastGs = event.getGlobalSequence();
                        }
                        processed++;
                    }

                    startVc = mergedVc;
                    startGs = lastGs;

                    projection.setRebuildProcessedEvents(processed);
                    projection.setProcessedVector(startVc);
                    if (lastEventId != null) {
                        projection.setLastProcessedEventId(lastEventId);
                    }
                    projectionRepository.save(projection);
                    projectionRepository.flush();

                    if (batch.size() < BATCH_SIZE) break;
                }

                conn.commit();

                projection.setStatus(ProjectionEntity.ProjectionStatus.RUNNING);
                projection.setProcessedCount(processed);
                projection.setLastProcessedAt(Instant.now());
                projection.setRebuildTotalEvents(processed);
                projectionRepository.save(projection);

                processQueuedDuringRebuild(projection);

            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            log.error("Rebuild failed for projection {}: {}", projection.getProjectionId(), e.getMessage(), e);
            markProjectionError(projection, "Rebuild failed: " + e.getMessage());
        }
    }

    private void processQueuedDuringRebuild(ProjectionEntity projection) {
        List<PendingProjectionEntity> pending = pendingProjectionRepository
            .findByProjectionIdAndStatusOrderByCreatedAtAsc(
                projection.getProjectionId(), PendingProjectionEntity.PendingStatus.PENDING
            );

        int processed = 0;
        for (PendingProjectionEntity pp : pending) {
            long startMs = System.currentTimeMillis();
            try {
                Optional<EventEntity> eventOpt = eventRepository.findById(pp.getEventId());
                if (eventOpt.isPresent()) {
                    applyEventToMaterializedView(projection, eventOpt.get());
                    pp.setStatus(PendingProjectionEntity.PendingStatus.PROCESSED);
                    pp.setProcessedAt(Instant.now());
                    long latency = System.currentTimeMillis() - startMs;
                    metricsTracker.recordProcessing(projection.getProjectionId(), latency, true);
                    processed++;
                } else {
                    pp.setStatus(PendingProjectionEntity.PendingStatus.FAILED);
                    pp.setErrorMessage("Event not found");
                    metricsTracker.recordProcessing(projection.getProjectionId(), 0, false);
                }
            } catch (Exception e) {
                log.warn("Failed to apply queued event during rebuild: {}", pp.getEventId(), e);
                pp.setStatus(PendingProjectionEntity.PendingStatus.FAILED);
                pp.setErrorMessage(e.getMessage());
                metricsTracker.recordProcessing(projection.getProjectionId(), 0, false);
            }
            pendingProjectionRepository.save(pp);
        }

        if (processed > 0) {
            projection.setProcessedCount(projection.getProcessedCount() + processed);
            projection.setLastProcessedAt(Instant.now());
            projectionRepository.save(projection);
        }
    }

    private void markProjectionError(ProjectionEntity projection, String errorMessage) {
        projection.setStatus(ProjectionEntity.ProjectionStatus.ERROR);
        projection.setErrorMessage(errorMessage);
        projection.setErrorAt(Instant.now());
        projectionRepository.save(projection);
        pauseDownstreamProjections(projection.getBaseProjectionId());
    }

    private boolean matchesProjection(ProjectionEntity projection, EventEntity event) {
        return JsonPathUtil.matchesPattern(event.getAggregateType(), projection.getAggregateTypePattern())
            && JsonPathUtil.matchesPattern(event.getEventType(), projection.getEventTypePattern());
    }

    private void applyEventToMaterializedView(ProjectionEntity projection, EventEntity event) {
        try (Connection conn = dataSource.getConnection()) {
            upsertMaterializedViewRow(conn, projection, event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to update materialized view", e);
        }
    }

    private void createMaterializedViewTable(String tableName, Map<String, Object> schema) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            StringBuilder sql = new StringBuilder();
            sql.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (");
            sql.append("aggregate_id VARCHAR(255) PRIMARY KEY,");
            sql.append("aggregate_type VARCHAR(255),");
            sql.append("last_event_id VARCHAR(64),");
            sql.append("last_event_type VARCHAR(255),");
            sql.append("updated_at TIMESTAMPTZ DEFAULT NOW()");

            if (schema != null) {
                Map<String, Object> fields = ProjectionSchemaUtil.getFieldsFromSchema(schema);
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    String fieldName = entry.getKey();
                    String type = ProjectionSchemaUtil.getTypeFromFieldDef(entry.getValue());
                    String sqlType = ProjectionSchemaUtil.getColumnTypeSql(type);
                    sql.append(",").append(escapeIdentifier(fieldName)).append(" ").append(sqlType);
                }
            }

            sql.append(")");
            stmt.execute(sql.toString());

            try {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_agg_type ON " + tableName + "(aggregate_type)");
            } catch (Exception e) {
                log.warn("Failed to create index on aggregate_type", e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create materialized view table: " + tableName, e);
        }
    }

    private void insertMaterializedViewRow(Connection conn, ProjectionEntity projection, EventEntity event) throws SQLException {
        Map<String, Object> beforeValues = readMvRow(projection.getTargetTable(), event.getAggregateId());
        boolean isInsert = (beforeValues == null);

        Map<String, Object> projectedValues = JsonPathUtil.extractFields(
            event.getPayload(), projection.getProjectionExpressions()
        );

        Map<String, Object> schemaFields = ProjectionSchemaUtil.getFieldsFromSchema(projection.getOutputSchema());
        Map<String, Object> finalValues = new LinkedHashMap<>();

        for (String fieldName : schemaFields.keySet()) {
            finalValues.put(fieldName, projectedValues.getOrDefault(fieldName, null));
        }

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(projection.getTargetTable());
        sql.append(" (aggregate_id, aggregate_type, last_event_id, last_event_type, updated_at");

        for (String field : finalValues.keySet()) {
            sql.append(", ").append(escapeIdentifier(field));
        }
        sql.append(") VALUES (?, ?, ?, ?, ?)");

        for (int i = 0; i < finalValues.size(); i++) {
            sql.append(", ?");
        }
        sql.append(") ON CONFLICT (aggregate_id) DO UPDATE SET ");
        sql.append("aggregate_type = EXCLUDED.aggregate_type,");
        sql.append("last_event_id = EXCLUDED.last_event_id,");
        sql.append("last_event_type = EXCLUDED.last_event_type,");
        sql.append("updated_at = EXCLUDED.updated_at");

        for (String field : finalValues.keySet()) {
            sql.append(", ").append(escapeIdentifier(field)).append(" = EXCLUDED.").append(escapeIdentifier(field));
        }

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, event.getAggregateId());
            ps.setString(idx++, event.getAggregateType());
            ps.setString(idx++, event.getEventId());
            ps.setString(idx++, event.getEventType());
            ps.setTimestamp(idx++, Timestamp.from(Instant.now()));

            for (Map.Entry<String, Object> entry : finalValues.entrySet()) {
                Object value = entry.getValue();
                setPreparedStatementValue(ps, idx++, value);
            }

            ps.executeUpdate();
        }

        Map<String, Object> afterValues = readMvRow(projection.getTargetTable(), event.getAggregateId());
        recordChangelogAsync(projection.getProjectionId(), event.getAggregateId(),
            isInsert ? MvChangelogEntity.ChangeType.INSERT : MvChangelogEntity.ChangeType.UPDATE,
            beforeValues, afterValues, event.getEventId());

        triggerDownstreamProjections(projection.getBaseProjectionId(), event.getAggregateId());
    }

    private void upsertMaterializedViewRow(Connection conn, ProjectionEntity projection, EventEntity event) throws SQLException {
        insertMaterializedViewRow(conn, projection, event);
    }

    private Map<String, Object> readMvRow(String targetTable, String aggregateId) {
        String sql = "SELECT * FROM " + targetTable + " WHERE aggregate_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, aggregateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnName(i), rs.getObject(i));
                    }
                    return row;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to read MV row for changelog", e);
        }
        return null;
    }

    private void recordChangelogAsync(String projectionId, String aggregateId,
                                       MvChangelogEntity.ChangeType changeType,
                                       Map<String, Object> beforeValues, Map<String, Object> afterValues,
                                       String triggerEventId) {
        try {
            MvChangelogEntity changelog = MvChangelogEntity.builder()
                .projectionId(projectionId)
                .aggregateId(aggregateId)
                .changeType(changeType)
                .beforeValue(beforeValues != null ? objectMapper.writeValueAsString(beforeValues) : null)
                .afterValue(afterValues != null ? objectMapper.writeValueAsString(afterValues) : null)
                .triggerEventId(triggerEventId)
                .createdAt(Instant.now())
                .build();
            mvChangelogRepository.save(changelog);
        } catch (Exception e) {
            log.warn("Failed to record changelog for projection {} aggregate {}: {}",
                projectionId, aggregateId, e.getMessage());
        }
    }

    private void triggerDownstreamProjections(String baseProjectionId, String aggregateId) {
        List<ProjectionEntity> downstreams = projectionRepository.findByUpstreamProjectionId(baseProjectionId);
        downstreams.sort(Comparator.comparing(ProjectionEntity::getCreatedAt));

        for (ProjectionEntity downstream : downstreams) {
            if (downstream.getStatus() != ProjectionEntity.ProjectionStatus.RUNNING) continue;

            try {
                PendingProjectionEntity pp = PendingProjectionEntity.builder()
                    .projectionId(downstream.getProjectionId())
                    .eventId("chain-" + UUID.randomUUID().toString().substring(0, 8))
                    .aggregateId(aggregateId)
                    .aggregateType("")
                    .eventType("UPSTREAM_CHANGE")
                    .status(PendingProjectionEntity.PendingStatus.PENDING)
                    .createdAt(Instant.now())
                    .build();
                pendingProjectionRepository.save(pp);
            } catch (Exception e) {
                log.warn("Failed to enqueue downstream projection {} for aggregate {}: {}",
                    downstream.getProjectionId(), aggregateId, e.getMessage());
            }
        }
    }

    private void setPreparedStatementValue(PreparedStatement ps, int index, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NULL);
        } else if (value instanceof String) {
            ps.setString(index, (String) value);
        } else if (value instanceof Integer) {
            ps.setInt(index, (Integer) value);
        } else if (value instanceof Long) {
            ps.setLong(index, (Long) value);
        } else if (value instanceof Double) {
            ps.setDouble(index, (Double) value);
        } else if (value instanceof Float) {
            ps.setFloat(index, (Float) value);
        } else if (value instanceof Boolean) {
            ps.setBoolean(index, (Boolean) value);
        } else if (value instanceof java.util.Date) {
            ps.setTimestamp(index, new Timestamp(((java.util.Date) value).getTime()));
        } else if (value instanceof Instant) {
            ps.setTimestamp(index, Timestamp.from((Instant) value));
        } else {
            try {
                ps.setString(index, objectMapper.writeValueAsString(value));
            } catch (Exception e) {
                ps.setString(index, value.toString());
            }
        }
    }

    private String escapeIdentifier(String identifier) {
        if (identifier == null) return "";
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public Map<String, Object> queryMaterializedView(String projectionId, int page, int pageSize,
                                                      String sortBy, String sortOrder,
                                                      Map<String, String> filters) {
        ProjectionEntity projection = resolveActiveProjection(projectionId);
        if (projection == null) {
            projection = projectionRepository.findById(projectionId)
                .orElseThrow(() -> new IllegalArgumentException("Projection not found: " + projectionId));
        }

        if (projection.getTargetTable() == null) {
            throw new IllegalArgumentException("Projection has no target table");
        }

        if (projection.getVersionStatus() == ProjectionEntity.VersionStatus.ARCHIVED) {
            if (projection.getArchivedAt() != null
                && projection.getArchivedAt().isBefore(Instant.now().minusSeconds(ARCHIVE_RETENTION_DAYS * 24 * 60 * 60))) {
                throw new IllegalArgumentException("版本已过期");
            }
        }

        return doQueryMaterializedView(projection, page, pageSize, sortBy, sortOrder, filters);
    }

    public Map<String, Object> queryMaterializedViewByVersion(String baseProjectionId, int version,
                                                               int page, int pageSize,
                                                               String sortBy, String sortOrder,
                                                               Map<String, String> filters) {
        ProjectionEntity projection = projectionRepository
            .findByBaseProjectionIdAndVersion(baseProjectionId, version)
            .orElseThrow(() -> new IllegalArgumentException("Projection version not found: " + baseProjectionId + " v" + version));

        if (projection.getVersionStatus() == ProjectionEntity.VersionStatus.ARCHIVED) {
            if (projection.getArchivedAt() != null
                && projection.getArchivedAt().isBefore(Instant.now().minusSeconds(ARCHIVE_RETENTION_DAYS * 24 * 60 * 60))) {
                throw new IllegalArgumentException("版本已过期");
            }
        }

        return doQueryMaterializedView(projection, page, pageSize, sortBy, sortOrder, filters);
    }

    private Map<String, Object> doQueryMaterializedView(ProjectionEntity projection, int page, int pageSize,
                                                         String sortBy, String sortOrder,
                                                         Map<String, String> filters) {
        Map<String, Object> schemaFields = ProjectionSchemaUtil.getFieldsFromSchema(projection.getOutputSchema());
        List<String> fieldNames = new ArrayList<>(schemaFields.keySet());

        StringBuilder countSql = new StringBuilder();
        countSql.append("SELECT COUNT(*) FROM ").append(projection.getTargetTable());

        StringBuilder dataSql = new StringBuilder();
        dataSql.append("SELECT ");
        if (fieldNames.isEmpty()) {
            dataSql.append("aggregate_id");
        } else {
            boolean first = true;
            for (String field : fieldNames) {
                if (!first) dataSql.append(", ");
                dataSql.append(escapeIdentifier(field));
                first = false;
            }
        }
        dataSql.append(" FROM ").append(projection.getTargetTable());

        List<Object> params = new ArrayList<>();
        if (filters != null && !filters.isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, String> entry : filters.entrySet()) {
                String col = entry.getKey();
                String val = entry.getValue();
                if (val == null || val.isEmpty()) continue;

                if (!fieldNames.contains(col)) continue;

                if (first) {
                    countSql.append(" WHERE ");
                    dataSql.append(" WHERE ");
                    first = false;
                } else {
                    countSql.append(" AND ");
                    dataSql.append(" AND ");
                }

                if (val.contains(">=") || val.contains("<=") || val.contains(">") || val.contains("<")) {
                    String op = val.startsWith(">=") ? ">=" : val.startsWith("<=") ? "<=" : val.startsWith(">") ? ">" : "<";
                    String numVal = val.substring(op.length());
                    countSql.append(escapeIdentifier(col)).append(" ").append(op).append(" ?");
                    dataSql.append(escapeIdentifier(col)).append(" ").append(op).append(" ?");
                    params.add(numVal);
                } else {
                    countSql.append(escapeIdentifier(col)).append(" = ?");
                    dataSql.append(escapeIdentifier(col)).append(" = ?");
                    params.add(val);
                }
            }
        }

        if (sortBy != null && !sortBy.isEmpty() && fieldNames.contains(sortBy)) {
            String order = "DESC".equalsIgnoreCase(sortOrder) ? "DESC" : "ASC";
            dataSql.append(" ORDER BY ").append(escapeIdentifier(sortBy)).append(" ").append(order);
        } else if (!fieldNames.isEmpty()) {
            dataSql.append(" ORDER BY ").append(escapeIdentifier(fieldNames.get(0))).append(" ASC");
        }

        if (pageSize > 0) {
            dataSql.append(" LIMIT ? OFFSET ?");
            params.add(pageSize);
            params.add(page * pageSize);
        }

        try (Connection conn = dataSource.getConnection()) {
            long total = 0;
            try (PreparedStatement ps = conn.prepareStatement(countSql.toString())) {
                for (int i = 0; i < params.size() - (pageSize > 0 ? 2 : 0); i++) {
                    ps.setString(i + 1, params.get(i).toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) total = rs.getLong(1);
                }
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(dataSql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    if (params.get(i) instanceof Number) {
                        ps.setLong(i + 1, ((Number) params.get(i)).longValue());
                    } else {
                        ps.setString(i + 1, params.get(i).toString());
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (String field : fieldNames) {
                            Object value = rs.getObject(field);
                            row.put(field, value);
                        }
                        rows.add(row);
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("total", total);
            result.put("page", page);
            result.put("pageSize", pageSize);
            result.put("rows", rows);
            result.put("schema", projection.getOutputSchema());
            return result;

        } catch (Exception e) {
            throw new RuntimeException("Failed to query materialized view", e);
        }
    }

    public long getPendingCount(String projectionId) {
        return pendingProjectionRepository.countByProjectionIdAndStatus(
            projectionId, PendingProjectionEntity.PendingStatus.PENDING
        );
    }

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void cleanupOldPendingRecords() {
        Instant cutoff = Instant.now().minusSeconds(7 * 24 * 60 * 60);
        int deleted = pendingProjectionRepository.deleteOldByStatus(
            cutoff, PendingProjectionEntity.PendingStatus.PROCESSED
        );
        if (deleted > 0) {
            log.info("Cleaned up {} old pending projection records", deleted);
        }
    }

    @Transactional
    public void enqueueEventForProjections(EventEntity event) {
        List<ProjectionEntity> projections = projectionRepository.findByStatusIn(
            Arrays.asList(ProjectionEntity.ProjectionStatus.RUNNING, ProjectionEntity.ProjectionStatus.REBUILDING)
        );

        for (ProjectionEntity projection : projections) {
            if (projection.getUpstreamProjectionId() != null) {
                continue;
            }
            if (matchesProjection(projection, event)) {
                if (pendingProjectionRepository.existsByProjectionIdAndEventIdAndStatus(
                        projection.getProjectionId(), event.getEventId(),
                        PendingProjectionEntity.PendingStatus.PENDING)) {
                    continue;
                }

                PendingProjectionEntity pp = PendingProjectionEntity.builder()
                    .projectionId(projection.getProjectionId())
                    .eventId(event.getEventId())
                    .aggregateId(event.getAggregateId())
                    .aggregateType(event.getAggregateType())
                    .eventType(event.getEventType())
                    .status(PendingProjectionEntity.PendingStatus.PENDING)
                    .createdAt(Instant.now())
                    .build();
                pendingProjectionRepository.save(pp);
            }
        }
    }

    @Transactional
    public void pauseProjection(String projectionId) {
        ProjectionEntity p = projectionRepository.findById(projectionId)
            .orElseThrow(() -> new IllegalArgumentException("Projection not found: " + projectionId));
        p.setStatus(ProjectionEntity.ProjectionStatus.STOPPED);
        p.setPauseReason("手动暂停");
        projectionRepository.save(p);
        pauseDownstreamProjections(p.getBaseProjectionId());
    }

    @Transactional
    public void resumeProjection(String projectionId) {
        ProjectionEntity p = projectionRepository.findById(projectionId)
            .orElseThrow(() -> new IllegalArgumentException("Projection not found: " + projectionId));
        if (p.getStatus() == ProjectionEntity.ProjectionStatus.ERROR) {
            p.setErrorMessage(null);
            p.setErrorAt(null);
        }
        p.setStatus(ProjectionEntity.ProjectionStatus.RUNNING);
        p.setPauseReason(null);
        projectionRepository.save(p);
        resumeDownstreamProjections(p.getBaseProjectionId());
    }

    private void pauseDownstreamProjections(String baseProjectionId) {
        List<ProjectionEntity> downstreams = projectionRepository.findByUpstreamProjectionId(baseProjectionId);
        for (ProjectionEntity ds : downstreams) {
            if (ds.getStatus() == ProjectionEntity.ProjectionStatus.RUNNING) {
                ds.setStatus(ProjectionEntity.ProjectionStatus.STOPPED);
                ds.setPauseReason("上游不可用");
                projectionRepository.save(ds);
                pauseDownstreamProjections(ds.getBaseProjectionId());
            }
        }
    }

    private void resumeDownstreamProjections(String baseProjectionId) {
        List<ProjectionEntity> downstreams = projectionRepository.findByUpstreamProjectionId(baseProjectionId);
        for (ProjectionEntity ds : downstreams) {
            if (ds.getStatus() == ProjectionEntity.ProjectionStatus.STOPPED
                && "上游不可用".equals(ds.getPauseReason())) {
                ds.setStatus(ProjectionEntity.ProjectionStatus.RUNNING);
                ds.setPauseReason(null);
                projectionRepository.save(ds);
                resumeDownstreamProjections(ds.getBaseProjectionId());
            }
        }
    }

    @Transactional
    public ProjectionEntity createProjectionVersion(String baseProjectionId,
                                                     Map<String, String> projectionExpressions,
                                                     Map<String, Object> outputSchema) {
        List<ProjectionEntity> existingVersions = projectionRepository
            .findByBaseProjectionIdOrderByVersionAsc(baseProjectionId);

        if (existingVersions.isEmpty()) {
            throw new IllegalArgumentException("Base projection not found: " + baseProjectionId);
        }

        ProjectionEntity currentActive = existingVersions.stream()
            .filter(p -> p.getVersionStatus() == ProjectionEntity.VersionStatus.ACTIVE)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No active version found for: " + baseProjectionId));

        int nextVersion = existingVersions.stream()
            .mapToInt(ProjectionEntity::getVersion)
            .max()
            .orElse(0) + 1;

        String newProjectionId = baseProjectionId + "_v" + nextVersion;
        String targetTable = "mv_" + newProjectionId.toLowerCase().replaceAll("[^a-z0-9_]", "_");

        Map<String, Object> schema = outputSchema != null ? outputSchema : currentActive.getOutputSchema();
        Map<String, String> expressions = projectionExpressions != null ? projectionExpressions : currentActive.getProjectionExpressions();

        createMaterializedViewTable(targetTable, schema);

        ProjectionEntity newVersion = ProjectionEntity.builder()
            .projectionId(newProjectionId)
            .name(currentActive.getName())
            .description(currentActive.getDescription())
            .aggregateTypePattern(currentActive.getAggregateTypePattern())
            .eventTypePattern(currentActive.getEventTypePattern())
            .projectionExpressions(expressions)
            .outputSchema(schema)
            .targetTable(targetTable)
            .updateStrategy(currentActive.getUpdateStrategy())
            .processedCount(0L)
            .rebuildTotalEvents(0L)
            .rebuildProcessedEvents(0L)
            .status(ProjectionEntity.ProjectionStatus.REBUILDING)
            .upstreamProjectionId(currentActive.getUpstreamProjectionId())
            .baseProjectionId(baseProjectionId)
            .version(nextVersion)
            .versionStatus(ProjectionEntity.VersionStatus.STANDBY)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        newVersion.setProcessedVector(new VectorClock(appConfig.getPartitions()));

        newVersion = projectionRepository.save(newVersion);

        triggerRebuild(newVersion.getProjectionId());

        return newVersion;
    }

    @Transactional
    public ProjectionEntity activateProjectionVersion(String baseProjectionId, int version) {
        ProjectionEntity target = projectionRepository
            .findByBaseProjectionIdAndVersion(baseProjectionId, version)
            .orElseThrow(() -> new IllegalArgumentException("Version not found: " + baseProjectionId + " v" + version));

        if (target.getVersionStatus() == ProjectionEntity.VersionStatus.ARCHIVED) {
            throw new IllegalArgumentException("Cannot activate archived version");
        }

        if (target.getStatus() == ProjectionEntity.ProjectionStatus.REBUILDING) {
            throw new IllegalArgumentException("版本尚未就绪");
        }

        long pendingCount = pendingProjectionRepository
            .countByProjectionIdAndStatus(target.getProjectionId(), PendingProjectionEntity.PendingStatus.PENDING);
        if (pendingCount > 0) {
            if (target.getUpstreamProjectionId() != null) {
                processChainedUpdate(target);
            } else {
                processRealtimeUpdates(target);
            }
            pendingCount = pendingProjectionRepository
                .countByProjectionIdAndStatus(target.getProjectionId(), PendingProjectionEntity.PendingStatus.PENDING);
            if (pendingCount > 0) {
                throw new IllegalArgumentException("版本尚未就绪");
            }
        }

        if (target.getVersionStatus() == ProjectionEntity.VersionStatus.ACTIVE) {
            return target;
        }

        ProjectionEntity currentActive = projectionRepository
            .findByBaseProjectionIdAndVersionStatus(baseProjectionId, ProjectionEntity.VersionStatus.ACTIVE)
            .orElse(null);

        if (currentActive != null) {
            currentActive.setVersionStatus(ProjectionEntity.VersionStatus.ARCHIVED);
            currentActive.setArchivedAt(Instant.now());
            currentActive.setStatus(ProjectionEntity.ProjectionStatus.STOPPED);
            projectionRepository.save(currentActive);
        }

        target.setVersionStatus(ProjectionEntity.VersionStatus.ACTIVE);
        if (target.getStatus() == ProjectionEntity.ProjectionStatus.STOPPED) {
            target.setStatus(ProjectionEntity.ProjectionStatus.RUNNING);
        }
        projectionRepository.save(target);

        return target;
    }

    public List<ProjectionEntity> listProjectionVersions(String baseProjectionId) {
        return projectionRepository.findByBaseProjectionIdOrderByVersionAsc(baseProjectionId);
    }

    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void refreshMetrics() {
        List<ProjectionEntity> projections = projectionRepository.findAll();
        Instant now = Instant.now();

        for (ProjectionEntity p : projections) {
            try {
                if (p.getVersionStatus() != ProjectionEntity.VersionStatus.ACTIVE) continue;

                ProjectionMetricsTracker.MetricsSnapshot snapshot = metricsTracker.computeMetrics(p.getProjectionId());

                if (snapshot.avgLatencyMs != null) {
                    p.setAvgLatencyMs(snapshot.avgLatencyMs);
                }
                if (snapshot.throughputPerMin != null) {
                    p.setThroughputPerMin(snapshot.throughputPerMin);
                }
                if (snapshot.errorRate != null) {
                    p.setErrorRate(snapshot.errorRate);
                }

                long rowCount = getMvRowCount(p.getTargetTable());
                p.setMvRowCount(rowCount);

                p.setHealthStatus(computeHealthStatus(p));
                p.setMetricsUpdatedAt(now);
                projectionRepository.save(p);
            } catch (Exception e) {
                log.warn("Failed to refresh metrics for projection {}: {}", p.getProjectionId(), e.getMessage());
            }
        }
    }

    private long getMvRowCount(String targetTable) {
        if (targetTable == null) return 0;
        String sql = "SELECT COUNT(*) FROM " + targetTable;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getLong(1);
        } catch (Exception e) {
            log.debug("Failed to get MV row count for {}", targetTable, e);
        }
        return 0;
    }

    private ProjectionEntity.HealthStatus computeHealthStatus(ProjectionEntity p) {
        if (p.getStatus() == ProjectionEntity.ProjectionStatus.ERROR) {
            return ProjectionEntity.HealthStatus.RED;
        }

        double latency = p.getAvgLatencyMs() != null ? p.getAvgLatencyMs() : 0;
        double errorRate = p.getErrorRate() != null ? p.getErrorRate() : 0;

        if (latency > 2000 || errorRate > 0.05) {
            return ProjectionEntity.HealthStatus.RED;
        } else if (latency > 500 || errorRate > 0.01) {
            return ProjectionEntity.HealthStatus.YELLOW;
        } else {
            return ProjectionEntity.HealthStatus.GREEN;
        }
    }

    public Map<String, Object> getProjectionMetrics(String projectionId) {
        ProjectionEntity p = resolveActiveProjection(projectionId);
        if (p == null) {
            p = projectionRepository.findById(projectionId)
                .orElseThrow(() -> new IllegalArgumentException("Projection not found: " + projectionId));
        }

        ProjectionEntity.HealthStatus health = p.getHealthStatus();
        if (health == null) {
            health = computeHealthStatus(p);
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("projectionId", p.getProjectionId());
        metrics.put("avgLatencyMs", p.getAvgLatencyMs());
        metrics.put("throughputPerMin", p.getThroughputPerMin());
        metrics.put("errorRate", p.getErrorRate());
        metrics.put("mvRowCount", p.getMvRowCount());
        metrics.put("healthStatus", health.name());
        metrics.put("metricsUpdatedAt", p.getMetricsUpdatedAt());
        metrics.put("status", p.getStatus().name());
        return metrics;
    }

    public Map<String, Object> queryChangelog(String projectionId, String aggregateId,
                                                Instant startTime, Instant endTime,
                                                int page, int pageSize) {
        ProjectionEntity p = resolveActiveProjection(projectionId);
        String effectiveProjectionId = p != null ? p.getProjectionId() : projectionId;

        Page<MvChangelogEntity> changelogPage;
        PageRequest pageRequest = PageRequest.of(page, pageSize);

        if (aggregateId != null && !aggregateId.isEmpty() && startTime != null && endTime != null) {
            changelogPage = mvChangelogRepository.findByProjectionIdAndAggregateIdAndCreatedAtBetween(
                effectiveProjectionId, aggregateId, startTime, endTime, pageRequest);
        } else if (startTime != null && endTime != null) {
            changelogPage = mvChangelogRepository.findByProjectionIdAndCreatedAtBetween(
                effectiveProjectionId, startTime, endTime, pageRequest);
        } else if (aggregateId != null && !aggregateId.isEmpty()) {
            changelogPage = mvChangelogRepository.findByProjectionIdAndAggregateId(
                effectiveProjectionId, aggregateId, pageRequest);
        } else {
            changelogPage = mvChangelogRepository.findByProjectionId(effectiveProjectionId, pageRequest);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", changelogPage.getTotalElements());
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("records", changelogPage.getContent().stream().map(this::changelogToMap).collect(Collectors.toList()));
        return result;
    }

    private Map<String, Object> changelogToMap(MvChangelogEntity c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("projectionId", c.getProjectionId());
        map.put("aggregateId", c.getAggregateId());
        map.put("changeType", c.getChangeType().name());
        map.put("beforeValue", c.getBeforeValue());
        map.put("afterValue", c.getAfterValue());
        map.put("triggerEventId", c.getTriggerEventId());
        map.put("createdAt", c.getCreatedAt());
        return map;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanupArchivedVersions() {
        Instant cutoff = Instant.now().minusSeconds(ARCHIVE_RETENTION_DAYS * 24 * 60 * 60);
        List<ProjectionEntity> expired = projectionRepository
            .findByVersionStatusAndArchivedAtBefore(ProjectionEntity.VersionStatus.ARCHIVED, cutoff);

        for (ProjectionEntity p : expired) {
            try {
                if (p.getTargetTable() != null) {
                    try (Connection conn = dataSource.getConnection();
                         Statement stmt = conn.createStatement()) {
                        stmt.execute("DROP TABLE IF EXISTS " + p.getTargetTable());
                    }
                }
                pendingProjectionRepository.deleteByProjectionId(p.getProjectionId());
                projectionRepository.delete(p);
                log.info("Cleaned up archived projection version: {} (archived at {})", p.getProjectionId(), p.getArchivedAt());
            } catch (Exception e) {
                log.warn("Failed to cleanup archived projection {}: {}", p.getProjectionId(), e.getMessage());
            }
        }
    }
}

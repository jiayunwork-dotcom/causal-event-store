package com.causal.eventstore.service;

import com.causal.eventstore.config.AppConfig;
import com.causal.eventstore.model.*;
import com.causal.eventstore.repository.EventRepository;
import com.causal.eventstore.repository.PendingProjectionRepository;
import com.causal.eventstore.repository.ProjectionRepository;
import com.causal.eventstore.util.JsonPathUtil;
import com.causal.eventstore.util.ProjectionSchemaUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.Date;

@Service
@Slf4j
public class ProjectionService {

    private final ProjectionRepository projectionRepository;
    private final EventRepository eventRepository;
    private final PendingProjectionRepository pendingProjectionRepository;
    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    private static final int BATCH_SIZE = 100;
    private static final int PENDING_BATCH_SIZE = 50;

    public ProjectionService(ProjectionRepository projectionRepository,
                             EventRepository eventRepository,
                             PendingProjectionRepository pendingProjectionRepository,
                             AppConfig appConfig,
                             ObjectMapper objectMapper,
                             DataSource dataSource) {
        this.projectionRepository = projectionRepository;
        this.eventRepository = eventRepository;
        this.pendingProjectionRepository = pendingProjectionRepository;
        this.appConfig = appConfig;
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
    }

    @Transactional
    public ProjectionEntity createProjection(String projectionId, String name, String description,
                                             String aggregateTypePattern, String eventTypePattern,
                                             Map<String, String> projectionExpressions,
                                             Map<String, Object> outputSchema,
                                             ProjectionEntity.UpdateStrategy updateStrategy) {
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
                .status(ProjectionEntity.ProjectionStatus.RUNNING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        p.setProcessedVector(new VectorClock(appConfig.getPartitions()));

        return projectionRepository.save(p);
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
        p.setErrorMessage(null);
        p.setErrorAt(null);
        projectionRepository.save(p);
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
            try {
                Optional<EventEntity> eventOpt = eventRepository.findById(pp.getEventId());
                if (eventOpt.isPresent()) {
                    applyEventToMaterializedView(projection, eventOpt.get());
                    pp.setStatus(PendingProjectionEntity.PendingStatus.PROCESSED);
                    pp.setProcessedAt(Instant.now());
                    processed++;
                } else {
                    pp.setStatus(PendingProjectionEntity.PendingStatus.FAILED);
                    pp.setErrorMessage("Event not found");
                }
            } catch (Exception e) {
                log.warn("Failed to process pending projection {} for event {}: {}", 
                    pp.getProjectionId(), pp.getEventId(), e.getMessage());
                pp.setStatus(PendingProjectionEntity.PendingStatus.FAILED);
                pp.setErrorMessage(e.getMessage());
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
                long total = projection.getRebuildTotalEvents() != null ? projection.getRebuildTotalEvents() : 0;

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

        for (PendingProjectionEntity pp : pending) {
            try {
                Optional<EventEntity> eventOpt = eventRepository.findById(pp.getEventId());
                if (eventOpt.isPresent()) {
                    applyEventToMaterializedView(projection, eventOpt.get());
                    pp.setStatus(PendingProjectionEntity.PendingStatus.PROCESSED);
                    pp.setProcessedAt(Instant.now());
                }
            } catch (Exception e) {
                log.warn("Failed to apply queued event during rebuild: {}", pp.getEventId(), e);
            }
            pendingProjectionRepository.save(pp);
        }
    }

    private void markProjectionError(ProjectionEntity projection, String errorMessage) {
        projection.setStatus(ProjectionEntity.ProjectionStatus.ERROR);
        projection.setErrorMessage(errorMessage);
        projection.setErrorAt(Instant.now());
        projectionRepository.save(projection);
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
    }

    private void upsertMaterializedViewRow(Connection conn, ProjectionEntity projection, EventEntity event) throws SQLException {
        insertMaterializedViewRow(conn, projection, event);
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
        ProjectionEntity projection = projectionRepository.findById(projectionId)
            .orElseThrow(() -> new IllegalArgumentException("Projection not found: " + projectionId));

        if (projection.getTargetTable() == null) {
            throw new IllegalArgumentException("Projection has no target table");
        }

        Map<String, Object> schemaFields = ProjectionSchemaUtil.getFieldsFromSchema(projection.getOutputSchema());
        List<String> fieldNames = new ArrayList<>(schemaFields.keySet());

        StringBuilder countSql = new StringBuilder();
        countSql.append("SELECT COUNT(*) FROM ").append(projection.getTargetTable());

        StringBuilder dataSql = new StringBuilder();
        dataSql.append("SELECT aggregate_id, aggregate_type, last_event_id, last_event_type, updated_at");
        for (String field : fieldNames) {
            dataSql.append(", ").append(escapeIdentifier(field));
        }
        dataSql.append(" FROM ").append(projection.getTargetTable());

        List<Object> params = new ArrayList<>();
        if (filters != null && !filters.isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, String> entry : filters.entrySet()) {
                String col = entry.getKey();
                String val = entry.getValue();
                if (val == null || val.isEmpty()) continue;

                boolean isSchemaField = fieldNames.contains(col) || "aggregate_id".equals(col) 
                    || "aggregate_type".equals(col) || "last_event_type".equals(col);
                if (!isSchemaField) continue;

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

        if (sortBy != null && !sortBy.isEmpty()) {
            boolean sortable = fieldNames.contains(sortBy) || "aggregate_id".equals(sortBy)
                || "aggregate_type".equals(sortBy) || "updated_at".equals(sortBy);
            if (sortable) {
                String order = "DESC".equalsIgnoreCase(sortOrder) ? "DESC" : "ASC";
                dataSql.append(" ORDER BY ").append(escapeIdentifier(sortBy)).append(" ").append(order);
            } else {
                dataSql.append(" ORDER BY updated_at DESC");
            }
        } else {
            dataSql.append(" ORDER BY updated_at DESC");
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
                        row.put("aggregateId", rs.getString("aggregate_id"));
                        row.put("aggregateType", rs.getString("aggregate_type"));
                        row.put("lastEventId", rs.getString("last_event_id"));
                        row.put("lastEventType", rs.getString("last_event_type"));
                        row.put("updatedAt", rs.getTimestamp("updated_at") != null 
                            ? rs.getTimestamp("updated_at").toInstant() : null);

                        for (String field : fieldNames) {
                            row.put(field, rs.getObject(field));
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
        projectionRepository.save(p);
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
        projectionRepository.save(p);
    }
}

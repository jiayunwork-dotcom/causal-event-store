package com.causal.eventstore.controller;

import com.causal.eventstore.dto.AppendResult;
import com.causal.eventstore.dto.EventReadResponse;
import com.causal.eventstore.dto.EventWriteRequest;
import com.causal.eventstore.model.EventEntity;
import com.causal.eventstore.model.ProjectionEntity;
import com.causal.eventstore.model.VectorClock;
import com.causal.eventstore.repository.EventRepository;
import com.causal.eventstore.service.*;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
@Slf4j
@CrossOrigin(origins = "*")
public class EventStoreRestController {

    private final EventStoreService eventStoreService;
    private final SnapshotService snapshotService;
    private final SubscriptionService subscriptionService;
    private final ProjectionService projectionService;
    private final ConflictDetectionService conflictDetectionService;
    private final ClusterService clusterService;
    private final EventRepository eventRepository;
    private final StateComputationService stateComputationService;

    public EventStoreRestController(EventStoreService eventStoreService,
                                    SnapshotService snapshotService,
                                    SubscriptionService subscriptionService,
                                    ProjectionService projectionService,
                                    ConflictDetectionService conflictDetectionService,
                                    ClusterService clusterService,
                                    EventRepository eventRepository,
                                    StateComputationService stateComputationService) {
        this.eventStoreService = eventStoreService;
        this.snapshotService = snapshotService;
        this.subscriptionService = subscriptionService;
        this.projectionService = projectionService;
        this.conflictDetectionService = conflictDetectionService;
        this.clusterService = clusterService;
        this.eventRepository = eventRepository;
        this.stateComputationService = stateComputationService;
    }

    @PostMapping("/events")
    public ResponseEntity<?> appendEvents(@RequestBody List<EventWriteRequest> requests) {
        try {
            AppendResult result = eventStoreService.appendEvents(requests);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/events/aggregate/{aggregateId}")
    public ResponseEntity<List<EventReadResponse>> readByAggregate(
            @PathVariable String aggregateId,
            @RequestParam(required = false) Long fromSequence,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false, defaultValue = "OR") String tagMode) {
        if (tags != null && !tags.isEmpty()) {
            return ResponseEntity.ok(eventStoreService.readByAggregateWithTags(aggregateId, fromSequence, tags, tagMode));
        }
        return ResponseEntity.ok(eventStoreService.readByAggregate(aggregateId, fromSequence));
    }

    @GetMapping("/events/aggregate/{aggregateId}/at")
    public ResponseEntity<?> getStateAtTimestamp(
            @PathVariable String aggregateId,
            @RequestParam String timestamp) {
        try {
            java.time.Instant ts = java.time.Instant.parse(timestamp);
            Map<String, Object> state = stateComputationService.computeStateAtTimestamp(aggregateId, ts);
            return ResponseEntity.ok(Map.of("aggregateId", aggregateId, "timestamp", timestamp, "state", state));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid timestamp format: " + e.getMessage()));
        }
    }

    @GetMapping("/events/aggregate/{aggregateId}/replay")
    public ResponseEntity<?> getReplayData(@PathVariable String aggregateId) {
        List<StateComputationService.StateAtStep> steps = stateComputationService.computeAllStates(aggregateId);
        return ResponseEntity.ok(Map.of("aggregateId", aggregateId, "steps", steps));
    }

    @PostMapping("/events/causal")
    public ResponseEntity<List<EventReadResponse>> readCausal(@RequestBody Map<String, Object> request) {
        List<Integer> clocks = (List<Integer>) request.getOrDefault("vectorClock", new ArrayList<>());
        VectorClock vc = new VectorClock(clocks);
        return ResponseEntity.ok(eventStoreService.readCausal(vc));
    }

    @Data
    @Builder
    public static class CausalGraphResponse {
        private List<EventReadResponse> events;
        private List<Map<String, String>> edges;
    }

    @PostMapping("/events/causal-graph")
    public ResponseEntity<CausalGraphResponse> getCausalGraph(@RequestBody List<String> eventIds) {
        List<EventEntity> events = eventRepository.findByEventIdIn(eventIds);
        Set<String> foundIds = events.stream().map(EventEntity::getEventId).collect(Collectors.toSet());
        Map<String, EventEntity> eventMap = new HashMap<>();
        for (EventEntity e : events) eventMap.put(e.getEventId(), e);

        List<Map<String, String>> edges = new ArrayList<>();
        for (EventEntity e : events) {
            if (e.getCausalDependencies() != null) {
                for (String dep : e.getCausalDependencies()) {
                    if (foundIds.contains(dep)) {
                        Map<String, String> edge = new HashMap<>();
                        edge.put("from", dep);
                        edge.put("to", e.getEventId());
                        edges.add(edge);
                    }
                }
            }
            for (EventEntity other : events) {
                if (other.getEventId().equals(e.getEventId())) continue;
                VectorClock vcA = other.getVectorClock();
                VectorClock vcB = e.getVectorClock();
                if (vcA != null && vcB != null && vcA.happensBefore(vcB)) {
                    boolean directEdge = !events.stream().anyMatch(mid ->
                            !mid.getEventId().equals(other.getEventId())
                                    && !mid.getEventId().equals(e.getEventId())
                                    && mid.getVectorClock() != null
                                    && vcA.happensBefore(mid.getVectorClock())
                                    && mid.getVectorClock().happensBefore(vcB));
                    if (directEdge) {
                        boolean alreadyExists = edges.stream()
                                .anyMatch(edge -> edge.get("from").equals(other.getEventId())
                                        && edge.get("to").equals(e.getEventId()));
                        if (!alreadyExists) {
                            Map<String, String> edge = new HashMap<>();
                            edge.put("from", other.getEventId());
                            edge.put("to", e.getEventId());
                            edge.put("type", "vector");
                            edges.add(edge);
                        }
                    }
                }
            }
        }

        CausalGraphResponse resp = CausalGraphResponse.builder()
                .events(events.stream().map(e -> EventReadResponse.builder()
                        .eventId(e.getEventId())
                        .aggregateId(e.getAggregateId())
                        .aggregateType(e.getAggregateType())
                        .eventType(e.getEventType())
                        .payload(e.getPayload())
                        .partitionId(e.getPartitionId())
                        .sequenceNumber(e.getSequenceNumber())
                        .globalSequence(e.getGlobalSequence())
                        .vectorClock(e.getVectorClock())
                        .causalDependencies(e.getCausalDependencies())
                        .timestamp(e.getTimestamp())
                        .tags(e.getTags())
                        .build()).collect(Collectors.toList()))
                .edges(edges)
                .build();
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/cluster/status")
    public ResponseEntity<?> getClusterStatus() {
        return ResponseEntity.ok(clusterService.getClusterStatus());
    }

    @GetMapping("/cluster/nodes")
    public ResponseEntity<?> listNodes() {
        return ResponseEntity.ok(clusterService.listNodes());
    }

    @GetMapping("/cluster/partition-counts")
    public ResponseEntity<?> getPartitionCounts() {
        return ResponseEntity.ok(clusterService.getPartitionEventCounts());
    }

    @PostMapping("/snapshots/{aggregateId}")
    public ResponseEntity<?> createSnapshot(@PathVariable String aggregateId) {
        try {
            return ResponseEntity.ok(snapshotService.createSnapshotManual(aggregateId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/snapshots/{aggregateId}")
    public ResponseEntity<?> listSnapshots(@PathVariable String aggregateId) {
        return ResponseEntity.ok(snapshotService.listSnapshots(aggregateId));
    }

    @DeleteMapping("/snapshots/{snapshotId}")
    public ResponseEntity<?> deleteSnapshot(@PathVariable Long snapshotId) {
        snapshotService.deleteSnapshot(snapshotId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<?> createSubscription(@RequestBody Map<String, Object> request) {
        String consumerId = (String) request.get("consumerId");
        String pattern = (String) request.getOrDefault("eventPattern", "*");
        List<Integer> vc = (List<Integer>) request.get("startVector");
        VectorClock start = vc != null ? new VectorClock(vc) : null;
        return ResponseEntity.ok(subscriptionService.createSubscription(consumerId, pattern, start));
    }

    @GetMapping("/subscriptions")
    public ResponseEntity<?> listSubscriptions() {
        return ResponseEntity.ok(subscriptionService.listSubscriptions());
    }

    @DeleteMapping("/subscriptions/{id}")
    public ResponseEntity<?> deleteSubscription(@PathVariable String id) {
        subscriptionService.deleteSubscription(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/projections")
    public ResponseEntity<?> listProjections() {
        return ResponseEntity.ok(projectionService.listProjections());
    }

    @GetMapping("/projections/{id}")
    public ResponseEntity<?> getProjection(@PathVariable String id) {
        return projectionService.getProjection(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/projections")
    public ResponseEntity<?> createProjection(@RequestBody Map<String, Object> request) {
        try {
            String id = (String) request.get("projectionId");
            String name = (String) request.get("name");
            String desc = (String) request.getOrDefault("description", "");
            String aggPattern = (String) request.getOrDefault("aggregateTypePattern", "*");
            String evtPattern = (String) request.getOrDefault("eventTypePattern", "*");
            String upstreamProjectionId = (String) request.get("upstreamProjectionId");
            
            @SuppressWarnings("unchecked")
            Map<String, String> expressions = (Map<String, String>) request.get("projectionExpressions");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> outputSchema = (Map<String, Object>) request.get("outputSchema");
            
            String strategyStr = (String) request.get("updateStrategy");
            ProjectionEntity.UpdateStrategy strategy = strategyStr != null 
                ? ProjectionEntity.UpdateStrategy.valueOf(strategyStr.toUpperCase())
                : ProjectionEntity.UpdateStrategy.REALTIME;

            return ResponseEntity.ok(projectionService.createProjection(
                id, name, desc, aggPattern, evtPattern, expressions, outputSchema, strategy, upstreamProjectionId
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PutMapping("/projections/{id}")
    public ResponseEntity<?> updateProjection(@PathVariable String id, @RequestBody Map<String, Object> request) {
        try {
            String name = (String) request.get("name");
            String desc = (String) request.get("description");
            String aggPattern = (String) request.get("aggregateTypePattern");
            String evtPattern = (String) request.get("eventTypePattern");
            
            @SuppressWarnings("unchecked")
            Map<String, String> expressions = (Map<String, String>) request.get("projectionExpressions");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> outputSchema = (Map<String, Object>) request.get("outputSchema");
            
            String strategyStr = (String) request.get("updateStrategy");
            ProjectionEntity.UpdateStrategy strategy = strategyStr != null 
                ? ProjectionEntity.UpdateStrategy.valueOf(strategyStr.toUpperCase())
                : null;

            return ResponseEntity.ok(projectionService.updateProjection(
                id, name, desc, aggPattern, evtPattern, expressions, outputSchema, strategy
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/projections/{id}/rebuild")
    public ResponseEntity<?> rebuildProjection(@PathVariable String id) {
        try {
            projectionService.triggerRebuild(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Rebuild triggered"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/projections/{id}/pause")
    public ResponseEntity<?> pauseProjection(@PathVariable String id) {
        try {
            projectionService.pauseProjection(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Projection paused"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/projections/{id}/resume")
    public ResponseEntity<?> resumeProjection(@PathVariable String id) {
        try {
            projectionService.resumeProjection(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Projection resumed"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/projections/{id}")
    public ResponseEntity<?> deleteProjection(@PathVariable String id) {
        projectionService.deleteProjection(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Projection deleted"));
    }

    @GetMapping("/projections/{id}/data")
    public ResponseEntity<?> queryMaterializedView(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "DESC") String sortOrder,
            @RequestParam Map<String, String> allParams) {
        try {
            Map<String, String> filters = new java.util.HashMap<>();
            for (Map.Entry<String, String> entry : allParams.entrySet()) {
                String key = entry.getKey();
                if (!"page".equals(key) && !"pageSize".equals(key) 
                    && !"sortBy".equals(key) && !"sortOrder".equals(key)) {
                    filters.put(key, entry.getValue());
                }
            }
            return ResponseEntity.ok(projectionService.queryMaterializedView(
                id, page, pageSize, sortBy, sortOrder, filters
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/projections/{id}/pending-count")
    public ResponseEntity<?> getPendingCount(@PathVariable String id) {
        try {
            long count = projectionService.getPendingCount(id);
            return ResponseEntity.ok(Map.of("projectionId", id, "pendingCount", count));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/projections/{id}/metrics")
    public ResponseEntity<?> getProjectionMetrics(@PathVariable String id) {
        try {
            return ResponseEntity.ok(projectionService.getProjectionMetrics(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/projections/{id}/changelog")
    public ResponseEntity<?> queryChangelog(
            @PathVariable String id,
            @RequestParam(required = false) String aggregateId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        try {
            java.time.Instant start = startTime != null ? java.time.Instant.parse(startTime) : null;
            java.time.Instant end = endTime != null ? java.time.Instant.parse(endTime) : null;
            return ResponseEntity.ok(projectionService.queryChangelog(id, aggregateId, start, end, page, pageSize));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid time format: " + e.getMessage()));
        }
    }

    @GetMapping("/projections/{id}/versions")
    public ResponseEntity<?> listProjectionVersions(@PathVariable String id) {
        return ResponseEntity.ok(projectionService.listProjectionVersions(id));
    }

    @PostMapping("/projections/{id}/versions")
    public ResponseEntity<?> createProjectionVersion(@PathVariable String id, @RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> expressions = (Map<String, String>) request.get("projectionExpressions");

            @SuppressWarnings("unchecked")
            Map<String, Object> outputSchema = (Map<String, Object>) request.get("outputSchema");

            return ResponseEntity.ok(projectionService.createProjectionVersion(id, expressions, outputSchema));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/projections/{id}/versions/{version}/activate")
    public ResponseEntity<?> activateProjectionVersion(@PathVariable String id, @PathVariable int version) {
        try {
            return ResponseEntity.ok(projectionService.activateProjectionVersion(id, version));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/projections/{id}/versions/{version}/data")
    public ResponseEntity<?> queryMaterializedViewByVersion(
            @PathVariable String id,
            @PathVariable int version,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "DESC") String sortOrder,
            @RequestParam Map<String, String> allParams) {
        try {
            Map<String, String> filters = new java.util.HashMap<>();
            for (Map.Entry<String, String> entry : allParams.entrySet()) {
                String key = entry.getKey();
                if (!"page".equals(key) && !"pageSize".equals(key)
                    && !"sortBy".equals(key) && !"sortOrder".equals(key)
                    && !"version".equals(key)) {
                    filters.put(key, entry.getValue());
                }
            }
            return ResponseEntity.ok(projectionService.queryMaterializedViewByVersion(
                id, version, page, pageSize, sortBy, sortOrder, filters
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/conflicts")
    public ResponseEntity<?> listConflicts(@RequestParam(required = false) String status) {
        if ("OPEN".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(conflictDetectionService.listOpenConflicts());
        }
        return ResponseEntity.ok(conflictDetectionService.listAllConflicts());
    }

    @GetMapping("/conflicts/aggregate/{aggregateId}")
    public ResponseEntity<?> listConflictsByAggregate(@PathVariable String aggregateId) {
        return ResponseEntity.ok(conflictDetectionService.listConflictsByAggregate(aggregateId));
    }

    @PostMapping("/conflicts/{id}/resolve")
    public ResponseEntity<?> resolveConflict(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        String res = (String) request.get("resolution");
        String notes = (String) request.getOrDefault("notes", "");
        com.causal.eventstore.model.ConflictEntity.ResolutionType rt =
                "KEEP_B".equalsIgnoreCase(res) ? com.causal.eventstore.model.ConflictEntity.ResolutionType.KEEP_B
                        : "CUSTOM".equalsIgnoreCase(res) ? com.causal.eventstore.model.ConflictEntity.ResolutionType.CUSTOM
                        : com.causal.eventstore.model.ConflictEntity.ResolutionType.KEEP_A;
        List<EventWriteRequest> events = (List<EventWriteRequest>) request.getOrDefault("resolutionEvents", new ArrayList<>());
        return ResponseEntity.ok(conflictDetectionService.resolveConflict(id, rt, notes, events));
    }
}

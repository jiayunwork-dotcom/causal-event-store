package com.causal.eventstore.controller;

import com.causal.eventstore.dto.AppendResult;
import com.causal.eventstore.dto.EventReadResponse;
import com.causal.eventstore.dto.EventWriteRequest;
import com.causal.eventstore.model.EventEntity;
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

    public EventStoreRestController(EventStoreService eventStoreService,
                                    SnapshotService snapshotService,
                                    SubscriptionService subscriptionService,
                                    ProjectionService projectionService,
                                    ConflictDetectionService conflictDetectionService,
                                    ClusterService clusterService,
                                    EventRepository eventRepository) {
        this.eventStoreService = eventStoreService;
        this.snapshotService = snapshotService;
        this.subscriptionService = subscriptionService;
        this.projectionService = projectionService;
        this.conflictDetectionService = conflictDetectionService;
        this.clusterService = clusterService;
        this.eventRepository = eventRepository;
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
            @RequestParam(required = false) Long fromSequence) {
        return ResponseEntity.ok(eventStoreService.readByAggregate(aggregateId, fromSequence));
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

    @PostMapping("/projections")
    public ResponseEntity<?> createProjection(@RequestBody Map<String, Object> request) {
        String id = (String) request.get("projectionId");
        String name = (String) request.get("name");
        String desc = (String) request.getOrDefault("description", "");
        String pattern = (String) request.get("eventTypePattern");
        String logic = (String) request.get("handlerLogic");
        String table = (String) request.get("targetTable");
        return ResponseEntity.ok(projectionService.createProjection(id, name, desc, pattern, logic, table));
    }

    @PostMapping("/projections/{id}/replay")
    public ResponseEntity<?> replayProjection(@PathVariable String id) {
        projectionService.triggerReplay(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/projections/{id}")
    public ResponseEntity<?> deleteProjection(@PathVariable String id) {
        projectionService.deleteProjection(id);
        return ResponseEntity.ok().build();
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

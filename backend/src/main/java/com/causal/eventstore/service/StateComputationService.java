package com.causal.eventstore.service;

import com.causal.eventstore.model.EventEntity;
import com.causal.eventstore.model.SnapshotEntity;
import com.causal.eventstore.model.VectorClock;
import com.causal.eventstore.repository.EventRepository;
import com.causal.eventstore.repository.SnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class StateComputationService {

    private final EventRepository eventRepository;
    private final SnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    public StateComputationService(EventRepository eventRepository,
                                   SnapshotRepository snapshotRepository,
                                   ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> computeStateAtTimestamp(String aggregateId, Instant timestamp) {
        List<EventEntity> allEvents = eventRepository
                .findByAggregateIdOrderBySequenceNumberAsc(aggregateId);

        List<EventEntity> eventsUpToTimestamp = allEvents.stream()
                .filter(e -> !e.getTimestamp().isAfter(timestamp))
                .collect(Collectors.toList());

        List<EventEntity> causallyConsistent = filterCausallyConsistent(allEvents, eventsUpToTimestamp, timestamp);

        SnapshotPoint snapshotPoint = findBestSnapshot(aggregateId, timestamp);

        ObjectNode state;
        int startIdx;

        if (snapshotPoint != null) {
            state = parseSnapshotState(snapshotPoint.state);
            startIdx = findStartIndex(causallyConsistent, snapshotPoint.lastSequence);
        } else {
            state = objectMapper.createObjectNode();
            startIdx = 0;
        }

        for (int i = startIdx; i < causallyConsistent.size(); i++) {
            EventEntity event = causallyConsistent.get(i);
            state = applyMergePatch(state, event.getPayload());
        }

        return objectMapper.convertValue(state, Map.class);
    }

    public List<StateAtStep> computeAllStates(String aggregateId) {
        List<EventEntity> allEvents = eventRepository
                .findByAggregateIdOrderBySequenceNumberAsc(aggregateId);

        SnapshotPoint snapshotPoint = findBestSnapshot(aggregateId, null);

        ObjectNode state;
        int startIdx;

        if (snapshotPoint != null) {
            state = parseSnapshotState(snapshotPoint.state);
            startIdx = findStartIndex(allEvents, snapshotPoint.lastSequence);
        } else {
            state = objectMapper.createObjectNode();
            startIdx = 0;
        }

        List<StateAtStep> results = new ArrayList<>();

        if (snapshotPoint != null) {
            results.add(StateAtStep.builder()
                    .step(-1)
                    .state(objectMapper.convertValue(state, Map.class))
                    .isSnapshot(true)
                    .build());
        }

        for (int i = startIdx; i < allEvents.size(); i++) {
            EventEntity event = allEvents.get(i);
            state = applyMergePatch(state, event.getPayload());
            results.add(StateAtStep.builder()
                    .step(i)
                    .eventId(event.getEventId())
                    .eventType(event.getEventType())
                    .sequenceNumber(event.getSequenceNumber())
                    .timestamp(event.getTimestamp())
                    .state(objectMapper.convertValue(state, Map.class))
                    .isSnapshot(false)
                    .build());
        }

        return results;
    }

    public Map<String, Object> computeStateAtStep(String aggregateId, int targetStep) {
        List<EventEntity> allEvents = eventRepository
                .findByAggregateIdOrderBySequenceNumberAsc(aggregateId);

        if (targetStep < 0 || allEvents.isEmpty()) {
            return Collections.emptyMap();
        }

        if (targetStep >= allEvents.size()) {
            targetStep = allEvents.size() - 1;
        }

        SnapshotPoint snapshotPoint = findBestSnapshot(aggregateId, null);

        ObjectNode state;
        int startIdx;

        if (snapshotPoint != null && snapshotPoint.lastSequence <= allEvents.get(targetStep).getSequenceNumber()) {
            state = parseSnapshotState(snapshotPoint.state);
            startIdx = findStartIndex(allEvents, snapshotPoint.lastSequence);
        } else {
            state = objectMapper.createObjectNode();
            startIdx = 0;
        }

        for (int i = startIdx; i <= targetStep && i < allEvents.size(); i++) {
            EventEntity event = allEvents.get(i);
            state = applyMergePatch(state, event.getPayload());
        }

        return objectMapper.convertValue(state, Map.class);
    }

    private List<EventEntity> filterCausallyConsistent(List<EventEntity> allEvents,
                                                       List<EventEntity> eventsUpToTimestamp,
                                                       Instant queryTimestamp) {
        List<EventEntity> result = new ArrayList<>();

        for (EventEntity event : eventsUpToTimestamp) {
            VectorClock eventVc = event.getVectorClock();
            if (eventVc == null) {
                result.add(event);
                continue;
            }

            boolean isCausallyDetermined = true;

            for (EventEntity other : allEvents) {
                if (other.getEventId().equals(event.getEventId())) continue;
                VectorClock otherVc = other.getVectorClock();
                if (otherVc != null && otherVc.isConcurrent(eventVc)) {
                    if (other.getTimestamp().isAfter(queryTimestamp)) {
                        isCausallyDetermined = false;
                        break;
                    }
                }
            }

            if (isCausallyDetermined) {
                result.add(event);
            }
        }

        return result;
    }

    private SnapshotPoint findBestSnapshot(String aggregateId, Instant timestamp) {
        List<SnapshotEntity> snapshots = snapshotRepository
                .findByAggregateIdOrderByLastSequenceDesc(aggregateId);

        if (snapshots.isEmpty()) {
            return null;
        }

        for (SnapshotEntity snapshot : snapshots) {
            if (timestamp == null) {
                return new SnapshotPoint(snapshot.getSnapshotState(), snapshot.getLastSequence());
            }
            List<EventEntity> snapEvents = eventRepository
                    .findByAggregateIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                            aggregateId, snapshot.getLastSequence());
            if (snapEvents.isEmpty() || !snapEvents.get(0).getTimestamp().isAfter(timestamp)) {
                return new SnapshotPoint(snapshot.getSnapshotState(), snapshot.getLastSequence());
            }
        }

        return null;
    }

    private int findStartIndex(List<EventEntity> events, Long lastSequence) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getSequenceNumber() > lastSequence) {
                return i;
            }
        }
        return events.size();
    }

    ObjectNode applyMergePatch(ObjectNode target, String patchJson) {
        try {
            JsonNode patch = objectMapper.readTree(patchJson);
            return applyMergePatch(target, patch);
        } catch (Exception e) {
            log.warn("Failed to parse merge patch: {}", patchJson, e);
            return target;
        }
    }

    ObjectNode applyMergePatch(ObjectNode target, JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            return target;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = patch.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            JsonNode value = field.getValue();

            if (value.isNull()) {
                target.remove(key);
            } else if (value.isObject() && target.has(key) && target.get(key).isObject()) {
                applyMergePatch((ObjectNode) target.get(key), value);
            } else {
                target.set(key, value);
            }
        }

        return target;
    }

    private ObjectNode parseSnapshotState(String stateJson) {
        try {
            JsonNode node = objectMapper.readTree(stateJson);
            if (node.isObject()) {
                ObjectNode clean = objectMapper.createObjectNode();
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (!field.getKey().startsWith("_")) {
                        clean.set(field.getKey(), field.getValue());
                    }
                }
                return clean;
            }
            return objectMapper.createObjectNode();
        } catch (Exception e) {
            log.warn("Failed to parse snapshot state", e);
            return objectMapper.createObjectNode();
        }
    }

    private static class SnapshotPoint {
        final String state;
        final Long lastSequence;

        SnapshotPoint(String state, Long lastSequence) {
            this.state = state;
            this.lastSequence = lastSequence;
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class StateAtStep {
        private int step;
        private String eventId;
        private String eventType;
        private Long sequenceNumber;
        private Instant timestamp;
        private Map<String, Object> state;
        private boolean isSnapshot;
    }
}

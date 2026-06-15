package com.causal.eventstore.service;

import com.causal.eventstore.config.AppConfig;
import com.causal.eventstore.model.EventEntity;
import com.causal.eventstore.model.SnapshotEntity;
import com.causal.eventstore.repository.AggregateRepository;
import com.causal.eventstore.repository.EventRepository;
import com.causal.eventstore.repository.SnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SnapshotService {

    private final SnapshotRepository snapshotRepository;
    private final EventRepository eventRepository;
    private final AggregateRepository aggregateRepository;
    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;

    private final Set<String> snapshotLocks = ConcurrentHashMap.newKeySet();

    public SnapshotService(SnapshotRepository snapshotRepository,
                           EventRepository eventRepository,
                           AggregateRepository aggregateRepository,
                           AppConfig appConfig, ObjectMapper objectMapper) {
        this.snapshotRepository = snapshotRepository;
        this.eventRepository = eventRepository;
        this.aggregateRepository = aggregateRepository;
        this.appConfig = appConfig;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SnapshotEntity createSnapshot(String aggregateId) {
        if (!snapshotLocks.add(aggregateId)) {
            throw new IllegalStateException("Snapshot already in progress for aggregate: " + aggregateId);
        }

        try {
            List<EventEntity> events = eventRepository.findByAggregateIdOrderBySequenceNumberAsc(aggregateId);
            if (events.isEmpty()) {
                throw new IllegalArgumentException("No events found for aggregate: " + aggregateId);
            }

            SnapshotEntity lastSnapshot = snapshotRepository
                    .findTopByAggregateIdOrderByLastSequenceDesc(aggregateId).orElse(null);

            Long startSequence = lastSnapshot != null ? lastSnapshot.getLastSequence() : 0L;
            List<EventEntity> incrementalEvents = events.stream()
                    .filter(e -> e.getSequenceNumber() > startSequence)
                    .toList();

            Map<String, Object> state = new HashMap<>();
            if (lastSnapshot != null && lastSnapshot.getSnapshotState() != null) {
                try {
                    Map<String, Object> prevState = objectMapper.readValue(lastSnapshot.getSnapshotState(), Map.class);
                    state.putAll(prevState);
                } catch (Exception e) {
                    log.warn("Failed to parse previous snapshot state", e);
                }
            }
            state.put("_eventsAppended", incrementalEvents.size());
            state.put("_lastEventId", events.get(events.size() - 1).getEventId());
            state.put("_eventCount", events.size());

            List<Map<String, Object>> appliedEvents = new ArrayList<>();
            for (EventEntity e : incrementalEvents) {
                Map<String, Object> ev = new HashMap<>();
                ev.put("eventId", e.getEventId());
                ev.put("eventType", e.getEventType());
                try {
                    ev.put("payload", objectMapper.readValue(e.getPayload(), Map.class));
                } catch (Exception ex) {
                    ev.put("payload", e.getPayload());
                }
                appliedEvents.add(ev);
            }
            state.put("_appliedEventsSinceLastSnapshot", appliedEvents);

            Long lastSequence = events.get(events.size() - 1).getSequenceNumber();
            String stateJson;
            try {
                stateJson = objectMapper.writeValueAsString(state);
            } catch (Exception e) {
                stateJson = "{\"error\":\"serialization_failed\"}";
            }

            SnapshotEntity snapshot = SnapshotEntity.builder()
                    .aggregateId(aggregateId)
                    .snapshotState(stateJson)
                    .lastSequence(lastSequence)
                    .sizeBytes((long) stateJson.getBytes().length)
                    .createdAt(Instant.now())
                    .build();

            SnapshotEntity saved = snapshotRepository.save(snapshot);

            int keepCount = appConfig.getSnapshot().getRetention();
            snapshotRepository.deleteOldSnapshots(aggregateId, keepCount);

            log.info("Snapshot created for aggregate {} at sequence {} (size={} bytes)",
                    aggregateId, lastSequence, saved.getSizeBytes());

            return saved;
        } finally {
            snapshotLocks.remove(aggregateId);
        }
    }

    @Transactional
    public SnapshotEntity createSnapshotManual(String aggregateId) {
        return createSnapshot(aggregateId);
    }

    public List<SnapshotEntity> listSnapshots(String aggregateId) {
        return snapshotRepository.findByAggregateIdOrderByLastSequenceDesc(aggregateId);
    }

    public Optional<SnapshotEntity> getLatestSnapshot(String aggregateId) {
        return snapshotRepository.findTopByAggregateIdOrderByLastSequenceDesc(aggregateId);
    }

    @Transactional
    public void deleteSnapshot(Long snapshotId) {
        snapshotRepository.deleteById(snapshotId);
    }

    @Transactional
    public void cleanupOldSnapshots(String aggregateId) {
        int keepCount = appConfig.getSnapshot().getRetention();
        snapshotRepository.deleteOldSnapshots(aggregateId, keepCount);
    }

    @Async
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void autoCreateSnapshots() {
        try {
            int threshold = appConfig.getSnapshot().getThreshold();
            List<String> aggregateIds = aggregateRepository.findAll().stream()
                    .map(a -> a.getAggregateId())
                    .toList();

            for (String aggregateId : aggregateIds) {
                Long eventCount = eventRepository.countByAggregateId(aggregateId);
                Long snapCount = snapshotRepository.countByAggregateId(aggregateId);

                long expectedSnapshots = eventCount / threshold;
                if (expectedSnapshots > snapCount && !snapshotLocks.contains(aggregateId)) {
                    try {
                        log.info("Auto-creating snapshot for {} (events={}, existing snaps={})",
                                aggregateId, eventCount, snapCount);
                        createSnapshot(aggregateId);
                    } catch (Exception e) {
                        log.warn("Auto snapshot failed for {}", aggregateId, e);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Auto snapshot sweep failed", e);
        }
    }
}

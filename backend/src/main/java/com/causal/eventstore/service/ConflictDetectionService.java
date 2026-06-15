package com.causal.eventstore.service;

import com.causal.eventstore.model.ConflictEntity;
import com.causal.eventstore.model.EventEntity;
import com.causal.eventstore.model.VectorClock;
import com.causal.eventstore.repository.ConflictRepository;
import com.causal.eventstore.repository.EventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ConflictDetectionService {

    private final ConflictRepository conflictRepository;
    private final EventRepository eventRepository;

    public ConflictDetectionService(ConflictRepository conflictRepository, EventRepository eventRepository) {
        this.conflictRepository = conflictRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional
    public void detectConflicts(List<EventEntity> newEvents) {
        Map<String, List<EventEntity>> byAggregate = newEvents.stream()
                .collect(Collectors.groupingBy(EventEntity::getAggregateId));

        for (Map.Entry<String, List<EventEntity>> entry : byAggregate.entrySet()) {
            String aggregateId = entry.getKey();
            List<EventEntity> aggNewEvents = entry.getValue();

            List<EventEntity> existing = eventRepository.findByAggregateIdOrderBySequenceNumberAsc(aggregateId);
            existing.removeIf(e -> aggNewEvents.stream().anyMatch(n -> n.getEventId().equals(e.getEventId())));

            List<EventEntity> allEvents = new ArrayList<>(existing);
            allEvents.addAll(aggNewEvents);

            for (int i = 0; i < aggNewEvents.size(); i++) {
                EventEntity newEvent = aggNewEvents.get(i);
                for (EventEntity existingEvent : existing) {
                    if (existingEvent.getEventId().equals(newEvent.getEventId())) continue;
                    if (isConcurrentAndSameAggregate(newEvent, existingEvent)) {
                        createConflict(aggregateId, newEvent.getEventId(), existingEvent.getEventId());
                    }
                }
            }

            for (int i = 0; i < aggNewEvents.size(); i++) {
                for (int j = i + 1; j < aggNewEvents.size(); j++) {
                    EventEntity a = aggNewEvents.get(i);
                    EventEntity b = aggNewEvents.get(j);
                    if (isConcurrentAndSameAggregate(a, b)) {
                        createConflict(aggregateId, a.getEventId(), b.getEventId());
                    }
                }
            }
        }
    }

    private boolean isConcurrentAndSameAggregate(EventEntity a, EventEntity b) {
        if (!a.getAggregateId().equals(b.getAggregateId())) return false;
        VectorClock vcA = a.getVectorClock();
        VectorClock vcB = b.getVectorClock();
        if (vcA == null || vcB == null) return false;
        return vcA.isConcurrent(vcB);
    }

    private void createConflict(String aggregateId, String eventAId, String eventBId) {
        String e1 = eventAId.compareTo(eventBId) < 0 ? eventAId : eventBId;
        String e2 = eventAId.compareTo(eventBId) < 0 ? eventBId : eventAId;

        Optional<ConflictEntity> existing = conflictRepository
                .findByAggregateIdAndEventAIdAndEventBIdAndStatus(aggregateId, e1, e2, ConflictEntity.ConflictStatus.OPEN);

        if (existing.isEmpty()) {
            ConflictEntity conflict = ConflictEntity.builder()
                    .aggregateId(aggregateId)
                    .eventAId(e1)
                    .eventBId(e2)
                    .detectedAt(Instant.now())
                    .status(ConflictEntity.ConflictStatus.OPEN)
                    .build();
            conflictRepository.save(conflict);
            log.info("Conflict detected: aggregate={}, events={} vs {}", aggregateId, e1, e2);
        }
    }

    @Transactional
    public ConflictEntity resolveConflict(Long conflictId, ConflictEntity.ResolutionType resolution,
                                          String resolutionNotes, List<EventWriteRequest> resolutionEvents) {
        ConflictEntity conflict = conflictRepository.findById(conflictId)
                .orElseThrow(() -> new IllegalArgumentException("Conflict not found: " + conflictId));

        if (conflict.getStatus() == ConflictEntity.ConflictStatus.RESOLVED) {
            return conflict;
        }

        conflict.setStatus(ConflictEntity.ConflictStatus.RESOLVED);
        conflict.setResolution(resolution);
        conflict.setResolutionNotes(resolutionNotes);
        conflict.setResolvedAt(Instant.now());
        conflictRepository.save(conflict);

        log.info("Conflict resolved: id={}, resolution={}", conflictId, resolution);
        return conflict;
    }

    public List<ConflictEntity> listOpenConflicts() {
        return conflictRepository.findByStatus(ConflictEntity.ConflictStatus.OPEN);
    }

    public List<ConflictEntity> listAllConflicts() {
        return conflictRepository.findAll();
    }

    public List<ConflictEntity> listConflictsByAggregate(String aggregateId) {
        return conflictRepository.findByAggregateId(aggregateId);
    }
}

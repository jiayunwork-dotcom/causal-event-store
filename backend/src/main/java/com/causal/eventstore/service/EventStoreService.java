package com.causal.eventstore.service;

import com.causal.eventstore.config.AppConfig;
import com.causal.eventstore.dto.AppendResult;
import com.causal.eventstore.dto.EventReadResponse;
import com.causal.eventstore.dto.EventWriteRequest;
import com.causal.eventstore.exception.BatchSizeExceededException;
import com.causal.eventstore.exception.DependencyCheckException;
import com.causal.eventstore.model.*;
import com.causal.eventstore.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EventStoreService {

    private final EventRepository eventRepository;
    private final AggregateRepository aggregateRepository;
    private final PartitionSequenceRepository partitionSequenceRepository;
    private final ConflictRepository conflictRepository;
    private final PartitionService partitionService;
    private final ConflictDetectionService conflictDetectionService;
    private final AppConfig appConfig;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<String, Object> aggregateLocks = new ConcurrentHashMap<>();

    public EventStoreService(EventRepository eventRepository,
                             AggregateRepository aggregateRepository,
                             PartitionSequenceRepository partitionSequenceRepository,
                             ConflictRepository conflictRepository,
                             PartitionService partitionService,
                             ConflictDetectionService conflictDetectionService,
                             AppConfig appConfig,
                             ApplicationEventPublisher eventPublisher) {
        this.eventRepository = eventRepository;
        this.aggregateRepository = aggregateRepository;
        this.partitionSequenceRepository = partitionSequenceRepository;
        this.conflictRepository = conflictRepository;
        this.partitionService = partitionService;
        this.conflictDetectionService = conflictDetectionService;
        this.appConfig = appConfig;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public AppendResult appendEvents(List<EventWriteRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return AppendResult.builder()
                    .success(false)
                    .message("Empty batch")
                    .build();
        }

        if (requests.size() > appConfig.getBatch().getMaxSize()) {
            throw new BatchSizeExceededException(requests.size(), appConfig.getBatch().getMaxSize());
        }

        Set<String> batchEventIds = requests.stream()
                .map(r -> r.getEventId() != null ? r.getEventId() : generateEventId())
                .collect(Collectors.toSet());

        List<String> missingDeps = validateDependencies(requests, batchEventIds);
        if (!missingDeps.isEmpty()) {
            throw new DependencyCheckException("Missing causal dependencies", missingDeps);
        }

        Map<String, Long> partitionSequences = new HashMap<>();
        Map<String, Long> aggregateSequences = new HashMap<>();
        Map<String, VectorClock> writtenVectorClocks = new HashMap<>();

        Map<String, AggregateEntity> aggregates = loadAggregates(requests);
        Map<String, EventEntity> writtenEvents = new LinkedHashMap<>();

        for (EventWriteRequest request : requests) {
            String eventId = request.getEventId() != null ? request.getEventId() : generateEventId();
            String aggregateId = request.getAggregateId();
            int partitionId = partitionService.getPartitionForAggregate(aggregateId);

            long partitionSeq = partitionSequences.getOrDefault(String.valueOf(partitionId),
                    getPartitionCurrentSequence(partitionId));
            partitionSeq++;
            partitionSequences.put(String.valueOf(partitionId), partitionSeq);

            long aggregateSeq = aggregateSequences.getOrDefault(aggregateId,
                    getAggregateCurrentSequence(aggregateId, aggregates));
            aggregateSeq++;
            aggregateSequences.put(aggregateId, aggregateSeq);

            VectorClock mergedVc = buildVectorClock(request, partitionId, partitionSeq,
                    writtenVectorClocks, request.getCausalDependencies());

            EventEntity event = EventEntity.builder()
                    .eventId(eventId)
                    .aggregateId(aggregateId)
                    .aggregateType(request.getAggregateType() != null ? request.getAggregateType()
                            : aggregates.getOrDefault(aggregateId, AggregateEntity.builder().build()).getAggregateType())
                    .eventType(request.getEventType())
                    .payload(request.getPayload() != null ? request.getPayload() : "{}")
                    .partitionId(partitionId)
                    .sequenceNumber(aggregateSeq)
                    .partitionSequenceNumber(partitionSeq)
                    .timestamp(request.getTimestamp() != null ? request.getTimestamp() : Instant.now())
                    .causalDependencies(request.getCausalDependencies() != null ? request.getCausalDependencies() : new ArrayList<>())
                    .build();
            event.setVectorClock(mergedVc);
            event.prePersist();

            writtenEvents.put(eventId, event);
            writtenVectorClocks.put(eventId, mergedVc);
        }

        updatePartitionSequences(partitionSequences);
        updateAggregates(aggregates, aggregateSequences);

        eventRepository.saveAll(writtenEvents.values());
        eventRepository.flush();

        List<String> savedIds = new ArrayList<>(writtenEvents.keySet());
        List<EventEntity> savedEvents = eventRepository.findByEventIdIn(savedIds);

        for (EventEntity event : savedEvents) {
            try {
                eventPublisher.publishEvent(new EventWrittenEvent(event));
            } catch (Exception e) {
                log.warn("Failed to publish event written event: {}", event.getEventId(), e);
            }
        }

        conflictDetectionService.detectConflicts(savedEvents);

        return AppendResult.builder()
                .success(true)
                .message("Successfully appended " + savedEvents.size() + " events")
                .writtenEventIds(savedEvents.stream().map(EventEntity::getEventId).collect(Collectors.toList()))
                .updatedVectorClock(VectorClock.merge(new ArrayList<>(writtenVectorClocks.values())))
                .build();
    }

    private List<String> validateDependencies(List<EventWriteRequest> requests, Set<String> batchEventIds) {
        Set<String> allDependencies = new HashSet<>();
        for (EventWriteRequest request : requests) {
            if (request.getCausalDependencies() != null) {
                allDependencies.addAll(request.getCausalDependencies());
            }
        }
        if (allDependencies.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> externalDeps = new HashSet<>(allDependencies);
        externalDeps.removeAll(batchEventIds);

        if (externalDeps.isEmpty()) {
            return Collections.emptyList();
        }

        List<EventEntity> found = eventRepository.findByEventIdIn(new ArrayList<>(externalDeps));
        Set<String> foundIds = found.stream().map(EventEntity::getEventId).collect(Collectors.toSet());

        List<String> missing = new ArrayList<>();
        for (String dep : externalDeps) {
            if (!foundIds.contains(dep)) {
                missing.add(dep);
            }
        }
        return missing;
    }

    private VectorClock buildVectorClock(EventWriteRequest request, int partitionId, long partitionSeq,
                                          Map<String, VectorClock> writtenClocks, List<String> dependencies) {
        int dims = partitionService.getPartitionCount();
        VectorClock base = new VectorClock(dims);

        if (request.getClientVectorClock() != null && !request.getClientVectorClock().isEmpty()) {
            List<Integer> clocks = new ArrayList<>();
            for (int i = 0; i < dims; i++) {
                clocks.add(i < request.getClientVectorClock().size() ? request.getClientVectorClock().get(i) : 0);
            }
            base = new VectorClock(clocks);
        }

        if (dependencies != null) {
            Map<String, EventEntity> storedDeps = new HashMap<>();
            List<String> needLookup = new ArrayList<>();
            for (String dep : dependencies) {
                if (writtenClocks.containsKey(dep)) {
                    base = base.merge(writtenClocks.get(dep));
                } else {
                    needLookup.add(dep);
                }
            }
            if (!needLookup.isEmpty()) {
                List<EventEntity> found = eventRepository.findByEventIdIn(needLookup);
                for (EventEntity e : found) {
                    storedDeps.put(e.getEventId(), e);
                }
                for (EventEntity e : storedDeps.values()) {
                    base = base.merge(e.getVectorClock());
                }
            }
        }

        return base.increment(partitionId);
    }

    private Map<String, AggregateEntity> loadAggregates(List<EventWriteRequest> requests) {
        Set<String> aggregateIds = requests.stream()
                .map(EventWriteRequest::getAggregateId)
                .collect(Collectors.toSet());

        List<AggregateEntity> existing = aggregateRepository.findAllById(aggregateIds);
        Map<String, AggregateEntity> result = new HashMap<>();
        for (AggregateEntity a : existing) {
            result.put(a.getAggregateId(), a);
        }

        for (EventWriteRequest req : requests) {
            if (!result.containsKey(req.getAggregateId())) {
                AggregateEntity newAgg = AggregateEntity.builder()
                        .aggregateId(req.getAggregateId())
                        .aggregateType(req.getAggregateType() != null ? req.getAggregateType() : "default")
                        .currentSequence(0L)
                        .build();
                result.put(req.getAggregateId(), newAgg);
            }
        }
        return result;
    }

    private long getPartitionCurrentSequence(int partitionId) {
        return partitionSequenceRepository.findById(partitionId)
                .map(PartitionSequenceEntity::getLastSequence)
                .orElse(0L);
    }

    private long getAggregateCurrentSequence(String aggregateId, Map<String, AggregateEntity> aggregates) {
        AggregateEntity agg = aggregates.get(aggregateId);
        return agg != null && agg.getCurrentSequence() != null ? agg.getCurrentSequence() : 0L;
    }

    private void updatePartitionSequences(Map<String, Long> sequences) {
        for (Map.Entry<String, Long> entry : sequences.entrySet()) {
            int partitionId = Integer.parseInt(entry.getKey());
            PartitionSequenceEntity pse = partitionSequenceRepository.findByIdForUpdate(partitionId)
                    .orElse(PartitionSequenceEntity.builder().partitionId(partitionId).lastSequence(0L).build());
            pse.setLastSequence(Math.max(pse.getLastSequence(), entry.getValue()));
            partitionSequenceRepository.save(pse);
        }
    }

    private void updateAggregates(Map<String, AggregateEntity> aggregates, Map<String, Long> sequences) {
        for (Map.Entry<String, Long> entry : sequences.entrySet()) {
            AggregateEntity agg = aggregates.get(entry.getKey());
            if (agg != null) {
                agg.setCurrentSequence(Math.max(agg.getCurrentSequence() != null ? agg.getCurrentSequence() : 0L,
                        entry.getValue()));
                agg.setUpdatedAt(Instant.now());
                aggregateRepository.save(agg);
            }
        }
    }

    public List<EventReadResponse> readByAggregate(String aggregateId, Long fromSequence) {
        List<EventEntity> events;
        if (fromSequence != null && fromSequence > 0) {
            events = eventRepository.findByAggregateIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                    aggregateId, fromSequence);
        } else {
            events = eventRepository.findByAggregateIdOrderBySequenceNumberAsc(aggregateId);
        }
        return events.stream().map(this::toReadResponse).collect(Collectors.toList());
    }

    public List<EventReadResponse> readCausal(VectorClock startVector) {
        int dims = partitionService.getPartitionCount();
        Long maxGlobalSeq = eventRepository.findMaxGlobalSequence().orElse(0L);
        if (maxGlobalSeq == null) maxGlobalSeq = 0L;

        List<EventEntity> allCandidates = new ArrayList<>();
        long batchSize = 1000;
        for (long gs = 0; gs <= maxGlobalSeq; gs += batchSize) {
            List<EventEntity> batch = eventRepository.findByGlobalSequenceGreaterThanOrderByGlobalSequenceAsc(gs);
            for (EventEntity e : batch) {
                Long eGs = e.getGlobalSequence();
                if ((eGs == null || eGs <= gs + batchSize) && e.getVectorClock() != null
                        && e.getVectorClock().strictlyGreaterThan(startVector)) {
                    allCandidates.add(e);
                }
            }
            if (batch.size() < batchSize) break;
        }

        return topologicalSort(allCandidates).stream()
                .map(this::toReadResponse)
                .collect(Collectors.toList());
    }

    private List<EventEntity> topologicalSort(List<EventEntity> events) {
        Map<String, EventEntity> eventMap = events.stream()
                .collect(Collectors.toMap(EventEntity::getEventId, e -> e));

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> graph = new HashMap<>();
        for (EventEntity e : events) {
            inDegree.put(e.getEventId(), 0);
            graph.put(e.getEventId(), new ArrayList<>());
        }

        for (EventEntity e : events) {
            if (e.getCausalDependencies() != null) {
                for (String dep : e.getCausalDependencies()) {
                    if (eventMap.containsKey(dep)) {
                        graph.get(dep).add(e.getEventId());
                        inDegree.put(e.getEventId(), inDegree.get(e.getEventId()) + 1);
                    }
                }
            }
        }

        List<EventEntity> vcSorted = events.stream()
                .sorted(Comparator.comparing((EventEntity e) -> sumVc(e.getVectorClock()))
                        .thenComparing(e -> e.getGlobalSequence() != null ? e.getGlobalSequence() : Long.MAX_VALUE))
                .collect(Collectors.toList());

        PriorityQueue<EventEntity> queue = new PriorityQueue<>(
                Comparator.comparing((EventEntity e) -> sumVc(e.getVectorClock()))
                        .thenComparing(e -> e.getGlobalSequence() != null ? e.getGlobalSequence() : Long.MAX_VALUE));

        for (EventEntity e : vcSorted) {
            if (inDegree.get(e.getEventId()) == 0) {
                queue.offer(e);
            }
        }

        List<EventEntity> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            EventEntity current = queue.poll();
            result.add(current);
            for (String nextId : graph.get(current.getEventId())) {
                inDegree.put(nextId, inDegree.get(nextId) - 1);
                if (inDegree.get(nextId) == 0) {
                    queue.offer(eventMap.get(nextId));
                }
            }
        }

        Set<String> added = result.stream().map(EventEntity::getEventId).collect(Collectors.toSet());
        for (EventEntity e : vcSorted) {
            if (!added.contains(e.getEventId())) {
                result.add(e);
            }
        }
        return result;
    }

    private int sumVc(VectorClock vc) {
        if (vc == null || vc.getClocks() == null) return 0;
        return vc.getClocks().stream().mapToInt(Integer::intValue).sum();
    }

    private EventReadResponse toReadResponse(EventEntity e) {
        return EventReadResponse.builder()
                .eventId(e.getEventId())
                .aggregateId(e.getAggregateId())
                .aggregateType(e.getAggregateType())
                .eventType(e.getEventType())
                .payload(e.getPayload())
                .partitionId(e.getPartitionId())
                .sequenceNumber(e.getSequenceNumber())
                .partitionSequenceNumber(e.getPartitionSequenceNumber())
                .globalSequence(e.getGlobalSequence())
                .vectorClock(e.getVectorClock())
                .causalDependencies(e.getCausalDependencies())
                .timestamp(e.getTimestamp())
                .build();
    }

    private String generateEventId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static class EventWrittenEvent {
        private final EventEntity event;

        public EventWrittenEvent(EventEntity event) {
            this.event = event;
        }

        public EventEntity getEvent() {
            return event;
        }
    }
}

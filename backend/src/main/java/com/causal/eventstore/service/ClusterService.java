package com.causal.eventstore.service;

import com.causal.eventstore.config.AppConfig;
import com.causal.eventstore.model.*;
import com.causal.eventstore.repository.ClusterNodeRepository;
import com.causal.eventstore.repository.EventRepository;
import com.causal.eventstore.repository.PartitionSequenceRepository;
import com.causal.eventstore.repository.ReplicationStatusRepository;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ClusterService {

    private final ClusterNodeRepository nodeRepository;
    private final ReplicationStatusRepository replicationStatusRepository;
    private final PartitionSequenceRepository partitionSequenceRepository;
    private final EventRepository eventRepository;
    private final AppConfig appConfig;

    public ClusterService(ClusterNodeRepository nodeRepository,
                          ReplicationStatusRepository replicationStatusRepository,
                          PartitionSequenceRepository partitionSequenceRepository,
                          EventRepository eventRepository,
                          AppConfig appConfig) {
        this.nodeRepository = nodeRepository;
        this.replicationStatusRepository = replicationStatusRepository;
        this.partitionSequenceRepository = partitionSequenceRepository;
        this.eventRepository = eventRepository;
        this.appConfig = appConfig;
    }

    @Data
    @Builder
    public static class ClusterStatus {
        private String nodeId;
        private String nodeRole;
        private List<ClusterNodeEntity> nodes;
        private Map<Integer, Long> partitionEventCounts;
        private Map<Integer, Long> partitionLeaderSequences;
        private Map<String, Map<Integer, ReplicationStatusEntity>> replicationByNode;
        private Long totalEvents;
        private Long totalAggregates;
        private Instant timestamp;
    }

    @Data
    @Builder
    public static class CausalBarrierResult {
        private boolean visible;
        private List<Integer> waitingPartitions;
    }

    @Transactional
    public ClusterNodeEntity registerNode(String nodeId, String role, String host, int grpcPort, int httpPort) {
        ClusterNodeEntity.NodeRole r = "FOLLOWER".equalsIgnoreCase(role)
                ? ClusterNodeEntity.NodeRole.FOLLOWER : ClusterNodeEntity.NodeRole.LEADER;
        ClusterNodeEntity node = nodeRepository.findById(nodeId).orElseGet(() ->
                ClusterNodeEntity.builder()
                        .nodeId(nodeId)
                        .nodeRole(r)
                        .host(host)
                        .grpcPort(grpcPort)
                        .httpPort(httpPort)
                        .status(ClusterNodeEntity.NodeStatus.UP)
                        .joinedAt(Instant.now())
                        .build()
        );
        node.setNodeRole(r);
        node.setHost(host);
        node.setGrpcPort(grpcPort);
        node.setHttpPort(httpPort);
        node.setStatus(ClusterNodeEntity.NodeStatus.UP);
        node.setLastHeartbeat(Instant.now());
        return nodeRepository.save(node);
    }

    @Transactional
    public void heartbeat() {
        String nodeId = appConfig.getReplication().getNodeId();
        Optional<ClusterNodeEntity> opt = nodeRepository.findById(nodeId);
        if (opt.isPresent()) {
            ClusterNodeEntity node = opt.get();
            node.setLastHeartbeat(Instant.now());
            nodeRepository.save(node);
        }
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void heartbeatAndHealthCheck() {
        heartbeat();
        List<ClusterNodeEntity> all = nodeRepository.findAll();
        for (ClusterNodeEntity n : all) {
            if (n.getLastHeartbeat() != null &&
                    Duration.between(n.getLastHeartbeat(), Instant.now()).getSeconds() > 30) {
                if (n.getStatus() != ClusterNodeEntity.NodeStatus.DOWN) {
                    n.setStatus(ClusterNodeEntity.NodeStatus.DOWN);
                    nodeRepository.save(n);
                    log.warn("Node marked as DOWN: {}", n.getNodeId());
                }
            }
        }
    }

    @Transactional
    public ReplicationStatusEntity reportReplicationProgress(String nodeId, int partitionId,
                                                              long sequence, String eventId, long lagSeconds) {
        ReplicationStatusEntity rs = replicationStatusRepository
                .findByPartitionIdAndNodeId(partitionId, nodeId)
                .orElseGet(() -> ReplicationStatusEntity.builder()
                        .nodeId(nodeId)
                        .partitionId(partitionId)
                        .lastAppliedSequence(0L)
                        .build());
        rs.setLastAppliedSequence(sequence);
        rs.setLastAppliedEventId(eventId);
        rs.setLastHeartbeat(Instant.now());
        rs.setLagSeconds(lagSeconds);
        return replicationStatusRepository.save(rs);
    }

    public CausalBarrierResult checkCausalBarrier(String nodeId, EventEntity event) {
        if (event == null || event.getCausalDependencies() == null || event.getCausalDependencies().isEmpty()) {
            return CausalBarrierResult.builder().visible(true).waitingPartitions(Collections.emptyList()).build();
        }

        if (event.getVectorClock() == null) {
            return CausalBarrierResult.builder().visible(true).waitingPartitions(Collections.emptyList()).build();
        }

        List<Integer> waiting = new ArrayList<>();
        VectorClock eventVc = event.getVectorClock();
        int dims = appConfig.getPartitions();

        List<ReplicationStatusEntity> nodeStatus = replicationStatusRepository.findByNodeIdOrderByPartitionId(nodeId);
        Map<Integer, Long> nodeSeqs = new HashMap<>();
        for (ReplicationStatusEntity s : nodeStatus) {
            nodeSeqs.put(s.getPartitionId(), s.getLastAppliedSequence());
        }

        for (int p = 0; p < dims; p++) {
            int required = eventVc.getPartition(p);
            long available = nodeSeqs.getOrDefault(p, 0L);
            if (available < required) {
                waiting.add(p);
            }
        }

        return CausalBarrierResult.builder()
                .visible(waiting.isEmpty())
                .waitingPartitions(waiting)
                .build();
    }

    public ClusterStatus getClusterStatus() {
        List<ClusterNodeEntity> nodes = nodeRepository.findAll();

        Map<Integer, Long> partitionCounts = new HashMap<>();
        List<Object[]> counts = eventRepository.countEventsPerPartition();
        for (Object[] row : counts) {
            int p = ((Number) row[0]).intValue();
            long c = ((Number) row[1]).longValue();
            partitionCounts.put(p, c);
        }

        Map<Integer, Long> leaderSeqs = new HashMap<>();
        List<PartitionSequenceEntity> pse = partitionSequenceRepository.findAll();
        for (PartitionSequenceEntity p : pse) {
            leaderSeqs.put(p.getPartitionId(), p.getLastSequence());
        }

        Map<String, Map<Integer, ReplicationStatusEntity>> replication = new HashMap<>();
        for (ReplicationStatusEntity s : replicationStatusRepository.findAll()) {
            replication.computeIfAbsent(s.getNodeId(), k -> new HashMap<>())
                    .put(s.getPartitionId(), s);
        }

        return ClusterStatus.builder()
                .nodeId(appConfig.getReplication().getNodeId())
                .nodeRole(appConfig.getReplication().getRole())
                .nodes(nodes)
                .partitionEventCounts(partitionCounts)
                .partitionLeaderSequences(leaderSeqs)
                .replicationByNode(replication)
                .totalEvents(eventRepository.count())
                .totalAggregates(eventRepository.countDistinctAggregates())
                .timestamp(Instant.now())
                .build();
    }

    public List<ClusterNodeEntity> listNodes() {
        return nodeRepository.findAll();
    }

    public Map<Integer, Long> getPartitionEventCounts() {
        Map<Integer, Long> result = new HashMap<>();
        List<Object[]> counts = eventRepository.countEventsPerPartition();
        for (Object[] row : counts) {
            int p = ((Number) row[0]).intValue();
            long c = ((Number) row[1]).longValue();
            result.put(p, c);
        }
        for (int i = 0; i < appConfig.getPartitions(); i++) {
            result.putIfAbsent(i, 0L);
        }
        return result;
    }
}

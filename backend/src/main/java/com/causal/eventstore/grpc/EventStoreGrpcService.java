package com.causal.eventstore.grpc;

import com.causal.eventstore.dto.AppendResult;
import com.causal.eventstore.dto.EventWriteRequest;
import com.causal.eventstore.exception.BatchSizeExceededException;
import com.causal.eventstore.exception.DependencyCheckException;
import com.causal.eventstore.model.*;
import com.causal.eventstore.service.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@GrpcService
@Slf4j
public class EventStoreGrpcService extends EventStoreServiceGrpc.EventStoreServiceImplBase {

    private final EventStoreService eventStoreService;
    private final SnapshotService snapshotService;
    private final ClusterService clusterService;
    private final PartitionService partitionService;

    public EventStoreGrpcService(EventStoreService eventStoreService,
                                 SnapshotService snapshotService,
                                 ClusterService clusterService,
                                 PartitionService partitionService) {
        this.eventStoreService = eventStoreService;
        this.snapshotService = snapshotService;
        this.clusterService = clusterService;
        this.partitionService = partitionService;
    }

    @Override
    public void appendEvents(AppendEventsRequest request, StreamObserver<AppendEventsResponse> responseObserver) {
        try {
            List<EventWriteRequest> writes = request.getEventsList().stream()
                    .map(ew -> EventWriteRequest.builder()
                            .eventId(ew.getEventId() != null && !ew.getEventId().isEmpty() ? ew.getEventId() : null)
                            .aggregateId(ew.getAggregateId())
                            .aggregateType(ew.getAggregateType())
                            .eventType(ew.getEventType())
                            .payload(ew.getPayload() != null && !ew.getPayload().isEmpty() ? ew.getPayload() : "{}")
                            .causalDependencies(ew.getCausalDependenciesList())
                            .clientVectorClock(ew.hasClientVectorClock() ? ew.getClientVectorClock().getClocksList() : null)
                            .timestamp(ew.getTimestamp() > 0 ? Instant.ofEpochMilli(ew.getTimestamp()) : null)
                            .build())
                    .collect(Collectors.toList());

            AppendResult result = eventStoreService.appendEvents(writes);

            AppendEventsResponse.Builder resp = AppendEventsResponse.newBuilder()
                    .setSuccess(result.isSuccess())
                    .setMessage(result.getMessage() != null ? result.getMessage() : "");

            if (result.getWrittenEventIds() != null) {
                resp.addAllWrittenEventIds(result.getWrittenEventIds());
            }
            if (result.getUpdatedVectorClock() != null) {
                resp.setUpdatedVectorClock(VectorClock.newBuilder()
                        .addAllClocks(result.getUpdatedVectorClock().getClocks())
                        .build());
            }
            if (result.getMissingDependencies() != null) {
                resp.addAllMissingDependencies(result.getMissingDependencies());
            }
            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (DependencyCheckException e) {
            AppendEventsResponse resp = AppendEventsResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage(e.getMessage())
                    .addAllMissingDependencies(e.getMissingEventIds())
                    .build();
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (BatchSizeExceededException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            log.error("AppendEvents failed", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void readByAggregate(ReadByAggregateRequest request, StreamObserver<ReadResponse> responseObserver) {
        try {
            List<com.causal.eventstore.dto.EventReadResponse> events =
                    eventStoreService.readByAggregate(request.getAggregateId(),
                            request.getFromSequence() > 0 ? request.getFromSequence() : null);

            ReadResponse.Builder resp = ReadResponse.newBuilder();
            for (com.causal.eventstore.dto.EventReadResponse e : events) {
                resp.addEvents(toGrpcEvent(e));
            }
            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("ReadByAggregate failed", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void readCausal(ReadCausalRequest request, StreamObserver<ReadResponse> responseObserver) {
        try {
            List<Integer> clocks = request.hasStartVector() ? request.getStartVector().getClocksList()
                    : Collections.emptyList();
            VectorClock vc = new VectorClock(new ArrayList<>(clocks));

            List<com.causal.eventstore.dto.EventReadResponse> events = eventStoreService.readCausal(vc);
            ReadResponse.Builder resp = ReadResponse.newBuilder();
            for (com.causal.eventstore.dto.EventReadResponse e : events) {
                resp.addEvents(toGrpcEvent(e));
            }
            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("ReadCausal failed", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void createSnapshot(CreateSnapshotRequest request, StreamObserver<CreateSnapshotResponse> responseObserver) {
        try {
            SnapshotEntity snap = snapshotService.createSnapshotManual(request.getAggregateId());
            CreateSnapshotResponse resp = CreateSnapshotResponse.newBuilder()
                    .setSuccess(true)
                    .setSnapshotId(snap.getSnapshotId() != null ? snap.getSnapshotId() : 0)
                    .setLastSequence(snap.getLastSequence() != null ? snap.getLastSequence() : 0)
                    .setSizeBytes(snap.getSizeBytes() != null ? snap.getSizeBytes() : 0)
                    .setCreatedAt(snap.getCreatedAt() != null ? snap.getCreatedAt().toEpochMilli() : 0)
                    .build();
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("CreateSnapshot failed", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void listSnapshots(ListSnapshotsRequest request, StreamObserver<ListSnapshotsResponse> responseObserver) {
        try {
            List<SnapshotEntity> snaps = snapshotService.listSnapshots(request.getAggregateId());
            ListSnapshotsResponse.Builder resp = ListSnapshotsResponse.newBuilder();
            for (SnapshotEntity s : snaps) {
                resp.addSnapshots(SnapshotInfo.newBuilder()
                        .setSnapshotId(s.getSnapshotId() != null ? s.getSnapshotId() : 0)
                        .setAggregateId(s.getAggregateId())
                        .setLastSequence(s.getLastSequence() != null ? s.getLastSequence() : 0)
                        .setSizeBytes(s.getSizeBytes() != null ? s.getSizeBytes() : 0)
                        .setCreatedAt(s.getCreatedAt() != null ? s.getCreatedAt().toEpochMilli() : 0)
                        .build());
            }
            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("ListSnapshots failed", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getClusterStatus(GetClusterStatusRequest request, StreamObserver<GetClusterStatusResponse> responseObserver) {
        try {
            ClusterService.ClusterStatus cs = clusterService.getClusterStatus();
            GetClusterStatusResponse.Builder resp = GetClusterStatusResponse.newBuilder()
                    .setLocalNodeId(cs.getNodeId() != null ? cs.getNodeId() : "")
                    .setLocalRole(cs.getNodeRole() != null ? cs.getNodeRole() : "")
                    .setTotalEvents(cs.getTotalEvents() != null ? cs.getTotalEvents() : 0)
                    .setTotalAggregates(cs.getTotalAggregates() != null ? cs.getTotalAggregates() : 0)
                    .setTimestamp(cs.getTimestamp() != null ? cs.getTimestamp().toEpochMilli() : 0);

            if (cs.getNodes() != null) {
                for (ClusterNodeEntity n : cs.getNodes()) {
                    resp.addNodes(ClusterNode.newBuilder()
                            .setNodeId(n.getNodeId())
                            .setNodeRole(n.getNodeRole() != null ? n.getNodeRole().name() : "")
                            .setHost(n.getHost() != null ? n.getHost() : "")
                            .setGrpcPort(n.getGrpcPort() != null ? n.getGrpcPort() : 0)
                            .setHttpPort(n.getHttpPort() != null ? n.getHttpPort() : 0)
                            .setStatus(n.getStatus() != null ? n.getStatus().name() : "")
                            .setJoinedAt(n.getJoinedAt() != null ? n.getJoinedAt().toEpochMilli() : 0)
                            .setLastHeartbeat(n.getLastHeartbeat() != null ? n.getLastHeartbeat().toEpochMilli() : 0)
                            .build());
                }
            }

            int partitions = partitionService.getPartitionCount();
            for (int p = 0; p < partitions; p++) {
                PartitionInfo.Builder pb = PartitionInfo.newBuilder()
                        .setPartitionId(p)
                        .setEventCount(cs.getPartitionEventCounts() != null ? cs.getPartitionEventCounts().getOrDefault(p, 0L) : 0)
                        .setLeaderSequence(cs.getPartitionLeaderSequences() != null ? cs.getPartitionLeaderSequences().getOrDefault(p, 0L) : 0);

                if (cs.getReplicationByNode() != null) {
                    for (Map.Entry<String, Map<Integer, ReplicationStatusEntity>> e : cs.getReplicationByNode().entrySet()) {
                        ReplicationStatusEntity rs = e.getValue().get(p);
                        if (rs != null) {
                            pb.putFollowerSequences(e.getKey(), rs.getLastAppliedSequence());
                            pb.putFollowerLagSeconds(e.getKey(), rs.getLagSeconds() != null ? rs.getLagSeconds() : 0L);
                        }
                    }
                }
                resp.addPartitions(pb.build());
            }

            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("GetClusterStatus failed", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private Event toGrpcEvent(com.causal.eventstore.dto.EventReadResponse e) {
        Event.Builder eb = Event.newBuilder()
                .setEventId(e.getEventId() != null ? e.getEventId() : "")
                .setAggregateId(e.getAggregateId() != null ? e.getAggregateId() : "")
                .setAggregateType(e.getAggregateType() != null ? e.getAggregateType() : "")
                .setEventType(e.getEventType() != null ? e.getEventType() : "")
                .setPayload(e.getPayload() != null ? e.getPayload() : "{}")
                .setPartitionId(e.getPartitionId() != null ? e.getPartitionId() : 0)
                .setSequenceNumber(e.getSequenceNumber() != null ? e.getSequenceNumber() : 0)
                .setGlobalSequence(e.getGlobalSequence() != null ? e.getGlobalSequence() : 0)
                .setTimestamp(e.getTimestamp() != null ? e.getTimestamp().toEpochMilli() : 0);
        if (e.getVectorClock() != null) {
            eb.setVectorClock(VectorClock.newBuilder().addAllClocks(e.getVectorClock().getClocks()).build());
        }
        if (e.getCausalDependencies() != null) {
            eb.addAllCausalDependencies(e.getCausalDependencies());
        }
        return eb.build();
    }
}

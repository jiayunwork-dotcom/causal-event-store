package com.causal.eventstore.service;

import com.causal.eventstore.dto.EventReadResponse;
import com.causal.eventstore.model.EventEntity;
import com.causal.eventstore.model.SubscriptionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class WebSocketPushService extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final SubscriptionService subscriptionService;

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final Map<String, WebSocketSession> sessionByConsumer = new ConcurrentHashMap<>();
    private final Map<String, SubscriptionEntity> subscriptions = new ConcurrentHashMap<>();

    public WebSocketPushService(ObjectMapper objectMapper, SubscriptionService subscriptionService) {
        this.objectMapper = objectMapper;
        this.subscriptionService = subscriptionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        String query = session.getUri().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=");
                if (kv.length == 2 && "consumerId".equals(kv[0])) {
                    sessionByConsumer.put(kv[1], session);
                    log.info("WebSocket connected: consumer={}", kv[1]);
                }
            }
        }
        log.info("WebSocket session established: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        sessionByConsumer.values().removeIf(s -> s == session);
        log.info("WebSocket session closed: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            Map msg = objectMapper.readValue(message.getPayload(), Map.class);
            String type = (String) msg.get("type");
            if ("ping".equals(type)) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("type", "pong"))));
            }
        } catch (Exception e) {
            log.warn("WebSocket message handling failed", e);
        }
    }

    public void registerSubscription(SubscriptionEntity sub) {
        subscriptions.put(sub.getSubscriptionId(), sub);
    }

    public void unregisterSubscription(String subscriptionId) {
        subscriptions.remove(subscriptionId);
    }

    @EventListener
    public void onEventWritten(EventStoreService.EventWrittenEvent event) {
        pushEvent(event.getEvent());
    }

    public void pushEvent(EventEntity event) {
        EventReadResponse response = toResponse(event);
        for (SubscriptionEntity sub : subscriptions.values()) {
            try {
                if (subscriptionService.matchesPattern(event.getEventType(), sub.getEventPattern())) {
                    WebSocketSession session = sessionByConsumer.get(sub.getConsumerId());
                    if (session != null && session.isOpen()) {
                        Map<String, Object> push = Map.of(
                                "type", "event",
                                "subscriptionId", sub.getSubscriptionId(),
                                "event", response
                        );
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(push)));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to push event {} to subscription {}", event.getEventId(), sub.getSubscriptionId(), e);
            }
        }
    }

    public void broadcast(Map<String, Object> msg) {
        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            return;
        }
        for (WebSocketSession s : sessions) {
            if (s.isOpen()) {
                try {
                    s.sendMessage(new TextMessage(json));
                } catch (Exception ignored) {
                }
            }
        }
    }

    private EventReadResponse toResponse(EventEntity e) {
        return EventReadResponse.builder()
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
                .build();
    }
}

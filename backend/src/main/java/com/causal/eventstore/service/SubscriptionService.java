package com.causal.eventstore.service;

import com.causal.eventstore.model.ConsumerCursorEntity;
import com.causal.eventstore.model.SubscriptionEntity;
import com.causal.eventstore.model.VectorClock;
import com.causal.eventstore.repository.ConsumerCursorRepository;
import com.causal.eventstore.repository.SubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Slf4j
public class SubscriptionService {

    private final ConsumerCursorRepository cursorRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final WebSocketPushService webSocketPushService;

    public SubscriptionService(ConsumerCursorRepository cursorRepository,
                               SubscriptionRepository subscriptionRepository,
                               WebSocketPushService webSocketPushService) {
        this.cursorRepository = cursorRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.webSocketPushService = webSocketPushService;
    }

    @Transactional
    public ConsumerCursorEntity getOrCreateCursor(String consumerId, int dimensions) {
        return cursorRepository.findById(consumerId).orElseGet(() -> {
            ConsumerCursorEntity cursor = ConsumerCursorEntity.builder()
                    .consumerId(consumerId)
                    .lastEventId(null)
                    .acknowledgedAt(Instant.now())
                    .build();
            cursor.setCursorVector(new VectorClock(dimensions));
            return cursorRepository.save(cursor);
        });
    }

    @Transactional
    public ConsumerCursorEntity acknowledgeCursor(String consumerId, VectorClock newVector, String lastEventId) {
        ConsumerCursorEntity cursor = cursorRepository.findById(consumerId)
                .orElseThrow(() -> new IllegalArgumentException("Consumer not found: " + consumerId));
        cursor.setCursorVector(newVector);
        cursor.setLastEventId(lastEventId);
        cursor.setAcknowledgedAt(Instant.now());
        return cursorRepository.save(cursor);
    }

    public Optional<ConsumerCursorEntity> getCursor(String consumerId) {
        return cursorRepository.findById(consumerId);
    }

    @Transactional
    public SubscriptionEntity createSubscription(String consumerId, String eventPattern, VectorClock startVector) {
        String subscriptionId = "sub-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        SubscriptionEntity sub = SubscriptionEntity.builder()
                .subscriptionId(subscriptionId)
                .consumerId(consumerId)
                .eventPattern(eventPattern)
                .lastPushAt(Instant.now())
                .build();
        sub.setCursorVector(startVector != null ? startVector : new VectorClock(8));
        SubscriptionEntity saved = subscriptionRepository.save(sub);
        webSocketPushService.registerSubscription(saved);
        log.info("Subscription created: id={}, consumer={}, pattern={}", subscriptionId, consumerId, eventPattern);
        return saved;
    }

    @Transactional
    public void deleteSubscription(String subscriptionId) {
        subscriptionRepository.deleteById(subscriptionId);
        webSocketPushService.unregisterSubscription(subscriptionId);
    }

    public List<SubscriptionEntity> listSubscriptions() {
        return subscriptionRepository.findAll();
    }

    public List<SubscriptionEntity> listSubscriptionsByConsumer(String consumerId) {
        return subscriptionRepository.findByConsumerId(consumerId);
    }

    public boolean matchesPattern(String eventType, String pattern) {
        if (pattern == null || pattern.isEmpty() || "*".equals(pattern)) return true;
        String regex = pattern.replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return Pattern.matches(regex, eventType);
    }

    @Transactional
    public SubscriptionEntity updateSubscriptionCursor(String subscriptionId, VectorClock newVector) {
        SubscriptionEntity sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));
        sub.setCursorVector(newVector);
        sub.setLastPushAt(Instant.now());
        return subscriptionRepository.save(sub);
    }
}

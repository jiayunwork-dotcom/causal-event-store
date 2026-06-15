package com.causal.eventstore.repository;

import com.causal.eventstore.model.SubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, String> {

    List<SubscriptionEntity> findByConsumerId(String consumerId);

    List<SubscriptionEntity> findByEventPatternLike(String pattern);
}

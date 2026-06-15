package com.causal.eventstore.repository;

import com.causal.eventstore.model.AggregateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AggregateRepository extends JpaRepository<AggregateEntity, String> {

    Optional<AggregateEntity> findByAggregateId(String aggregateId);

    List<AggregateEntity> findByAggregateType(String aggregateType);

    List<AggregateEntity> findAllByOrderByUpdatedAtDesc();
}

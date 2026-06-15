package com.causal.eventstore.repository;

import com.causal.eventstore.model.ConsumerCursorEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConsumerCursorRepository extends JpaRepository<ConsumerCursorEntity, String> {
}

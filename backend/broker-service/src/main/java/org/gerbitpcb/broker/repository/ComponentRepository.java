package org.gerbitpcb.broker.repository;

import org.gerbitpcb.broker.domain.Component;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComponentRepository extends JpaRepository<Component, UUID> {
    Optional<Component> findBySku(String sku);
}

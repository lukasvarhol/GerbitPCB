package org.gerbitpcb.supplier.murata.repository;

import org.gerbitpcb.supplier.murata.domain.Component;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComponentRepository extends JpaRepository<Component, UUID> {
    Optional<Component> findBySku(String sku);
}


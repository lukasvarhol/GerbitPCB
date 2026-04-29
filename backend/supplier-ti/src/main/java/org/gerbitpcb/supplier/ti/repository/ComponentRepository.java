package org.gerbitpcb.supplier.ti.repository;

import org.gerbitpcb.supplier.ti.domain.Component;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComponentRepository extends JpaRepository<Component, UUID> {
    Optional<Component> findBySku(String sku);
}


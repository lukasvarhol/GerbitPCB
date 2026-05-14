package org.gerbitpcb.broker.repository;

import org.gerbitpcb.broker.domain.Transaction;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
}


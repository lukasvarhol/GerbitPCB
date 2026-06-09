package com.gerbitpcb.broker.repositroy;

import com.gerbit.broker.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, String> {}

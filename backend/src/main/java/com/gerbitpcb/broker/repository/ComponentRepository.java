package com.gerbitpcb.broker.repository;

import com.gerbitpcb.broker.model.Component;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComponentRepository extends JpaRepository<Component, String> {}

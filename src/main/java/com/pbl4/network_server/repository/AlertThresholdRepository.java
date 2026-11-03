package com.pbl4.network_server.repository;

import com.pbl4.network_server.entity.AlertThreshold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AlertThresholdRepository extends JpaRepository<AlertThreshold, Long> {
}
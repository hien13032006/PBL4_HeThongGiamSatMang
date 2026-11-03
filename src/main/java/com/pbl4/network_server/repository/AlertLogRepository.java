package com.pbl4.network_server.repository;

import com.pbl4.network_server.entity.AlertLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlertLogRepository extends JpaRepository<AlertLog, Long> {
    // Lấy 50 log cảnh báo mới nhất sắp xếp theo thời gian giảm dần
    List<AlertLog> findTop50ByOrderByCreatedAtDesc();
    Optional<AlertLog> findFirstByAgentIdOrderByCreatedAtDesc(String agentId);
}
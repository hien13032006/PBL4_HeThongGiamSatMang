package com.pbl4.network_server.repository;

import com.pbl4.network_server.entity.MetricsHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetricsHistoryRepository extends JpaRepository<MetricsHistory, Long> {
    
    // Truy vấn lịch sử metrics của 1 agent trong khoảng thời gian để vẽ biểu đồ
    List<MetricsHistory> findByAgentAgentIdAndTimestampBetweenOrderByTimestampAsc(
            String agentId, LocalDateTime start, LocalDateTime end);
}

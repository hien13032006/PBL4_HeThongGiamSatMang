package com.pbl4.network_server.repository;

import com.pbl4.network_server.entity.MetricsHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MetricsHistoryRepository extends JpaRepository<MetricsHistory, Long> {
    
    // 1. Truy vấn lịch sử metrics của 1 agent trong khoảng thời gian để vẽ biểu đồ
    List<MetricsHistory> findByAgentAgentIdAndTimestampBetweenOrderByTimestampAsc(
            String agentId, LocalDateTime start, LocalDateTime end);

    // 2. Lấy 20 bản ghi mới nhất của 1 Agent (dùng cho REST API cũ)
    List<MetricsHistory> findTop20ByAgentAgentIdOrderByTimestampDesc(String agentId);

    // 3. Lấy 1 bản ghi mới nhất trong CSDL của 1 Agent (dành cho API Summary)
    Optional<MetricsHistory> findTopByAgentAgentIdOrderByTimestampDesc(String agentId);

    // 4. Tính tổng RX (KB) tích lũy trong CSDL của Agent
    @Query("SELECT COALESCE(SUM(m.rxRate), 0.0) FROM MetricsHistory m WHERE m.agent.agentId = :agentId")
    Double sumTotalRxByAgentId(@Param("agentId") String agentId);
    // 5. Tính tổng TX (KB) tích lũy trong CSDL của Agent
    @Query("SELECT COALESCE(SUM(m.txRate), 0.0) FROM MetricsHistory m WHERE m.agent.agentId = :agentId")
    Double sumTotalTxByAgentId(@Param("agentId") String agentId);
}
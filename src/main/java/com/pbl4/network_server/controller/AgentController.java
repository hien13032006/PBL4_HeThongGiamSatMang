package com.pbl4.network_server.controller;

import com.pbl4.network_server.entity.Agent;
import com.pbl4.network_server.entity.MetricsHistory;
import com.pbl4.network_server.repository.AgentRepository;
import com.pbl4.network_server.repository.MetricsHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // Cho phép Frontend gọi REST API không bị chặn CORS
public class AgentController {

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private MetricsHistoryRepository metricsHistoryRepository;

    // API 1: Lấy danh sách tất cả Agent từ Database
    @GetMapping("/agents")
    public List<Agent> getAllAgents() {
        return agentRepository.findAll();
    }

    // API 2: Lấy 20 bản ghi lịch sử metrics gần nhất của 1 Agent
    @GetMapping("/metrics/{agentId}")
    public List<MetricsHistory> getLatestMetrics(@PathVariable String agentId) {
        return metricsHistoryRepository.findTop20ByAgentAgentIdOrderByTimestampDesc(agentId);
    }

    @GetMapping("/agents/{agentId}/summary")
    public ResponseEntity<?> getAgentSummary(@PathVariable String agentId) {
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }

        // Lấy bản ghi metrics mới nhất từ DB
        MetricsHistory latestMetrics = metricsHistoryRepository.findTopByAgentAgentIdOrderByTimestampDesc(agentId).orElse(null);

        // Tính tổng tích lũy RX, TX từ DB (Đổi ra GB)
        Double totalRxKB = metricsHistoryRepository.sumTotalRxByAgentId(agentId);
        Double totalTxKB = metricsHistoryRepository.sumTotalTxByAgentId(agentId);

        double totalRxGB = (totalRxKB != null) ? totalRxKB / (1024 * 1024) : 0.0;
        double totalTxGB = (totalTxKB != null) ? totalTxKB / (1024 * 1024) : 0.0;

        // Đóng gói DTO gửi về Frontend
        Map<String, Object> response = new HashMap<>();
        response.put("agent", agent);
        response.put("latestMetrics", latestMetrics);
        response.put("totalRxGB", String.format("%.2f", totalRxGB));
        response.put("totalTxGB", String.format("%.2f", totalTxGB));

        return ResponseEntity.ok(response);
    }
}
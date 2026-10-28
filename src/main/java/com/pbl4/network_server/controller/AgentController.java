package com.pbl4.network_server.controller;

import com.pbl4.network_server.entity.Agent;
import com.pbl4.network_server.entity.MetricsHistory;
import com.pbl4.network_server.repository.AgentRepository;
import com.pbl4.network_server.repository.MetricsHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // API 3: Lấy thông tin tóm tắt và tổng dung lượng RX/TX của 1 Agent
    @GetMapping("/agents/{agentId}/summary")
    public ResponseEntity<?> getAgentSummary(@PathVariable String agentId) {
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }

        // Lấy bản ghi metrics mới nhất từ DB
        MetricsHistory latestMetrics = metricsHistoryRepository
                .findTopByAgentAgentIdOrderByTimestampDesc(agentId)
                .orElse(null);

        // Tính tổng tích lũy RX, TX từ DB (Dữ liệu DB đang là MB/s, chia 1024 để ra GB)
        Double totalRxMB = metricsHistoryRepository.sumTotalRxByAgentId(agentId);
        Double totalTxMB = metricsHistoryRepository.sumTotalTxByAgentId(agentId);

        double totalRxGB = (totalRxMB != null) ? (totalRxMB / 1024.0) : 0.0;
        double totalTxGB = (totalTxMB != null) ? (totalTxMB / 1024.0) : 0.0;

        // Làm tròn 2 chữ số thập phân
        totalRxGB = Math.round(totalRxGB * 100.0) / 100.0;
        totalTxGB = Math.round(totalTxGB * 100.0) / 100.0;

        // Đóng gói DTO gửi về Frontend
        Map<String, Object> response = new HashMap<>();
        response.put("agent", agent);
        response.put("latestMetrics", latestMetrics);
        response.put("totalRxGB", totalRxGB);
        response.put("totalTxGB", totalTxGB);

        return ResponseEntity.ok(response);
    }

    // API 4: Tra cứu lịch sử Metrics từ Database theo khoảng thời gian
    @GetMapping("/history")
    public ResponseEntity<?> getMetricsHistory(
            @RequestParam("agentId") String agentId,
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {

        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }

        // Truy vấn dữ liệu lưu trong MySQL
        List<MetricsHistory> historyList = metricsHistoryRepository
                .findByAgentAgentIdAndTimestampBetweenOrderByTimestampAsc(agentId, start, end);

        Map<String, Object> response = new HashMap<>();
        response.put("agentId", agentId);
        response.put("hostname", agent.getHostname());
        response.put("totalRecords", historyList.size());
        response.put("data", historyList);

        return ResponseEntity.ok(response);
    }
}
package com.pbl4.network_server.controller;

import com.pbl4.network_server.entity.Agent;
import com.pbl4.network_server.entity.MetricsHistory;
import com.pbl4.network_server.service.AgentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AgentController {

    @Autowired
    private AgentService agentService;

    // API 1: Lấy danh sách tất cả Agent -> URL: /api/agents
    @GetMapping("/agents")
    public List<Agent> getAllAgents() {
        return agentService.getAllAgents();
    }

    // API 2: Lấy 20 bản ghi lịch sử metrics mới nhất của 1 Agent -> URL: /api/metrics/{agentId}
    @GetMapping("/metrics/{agentId}")
    public List<MetricsHistory> getLatestMetrics(@PathVariable("agentId") String agentId) {
        return agentService.getLatestMetrics(agentId);
    }

    // API 3: Lấy thông tin tóm tắt và % tải mạng -> URL: /api/agents/{agentId}/summary
    @GetMapping("/agents/{agentId}/summary")
    public ResponseEntity<?> getAgentSummary(@PathVariable("agentId") String agentId) {
        Map<String, Object> summary = agentService.getAgentSummary(agentId);
        if (summary == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(summary);
    }

    // API 4: Tra cứu lịch sử Metrics theo khoảng thời gian -> URL: /api/history
    @GetMapping("/history")
    public ResponseEntity<?> getMetricsHistory(
            @RequestParam("agentId") String agentId,
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {

        Map<String, Object> history = agentService.getMetricsHistory(agentId, start, end);
        if (history == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(history);
    }

    // API 5: Xóa Agent và tất cả dữ liệu liên quan -> URL: /api/agents/{agentId}
    @DeleteMapping("/agents/{agentId}")
    public ResponseEntity<?> deleteAgent(@PathVariable("agentId") String agentId) {
        boolean deleted = agentService.deleteAgent(agentId);
        if (deleted) {
            return ResponseEntity.ok(Map.of("message", "Xóa Agent thành công!"));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Không tìm thấy Agent cần xóa!"));
    }
}
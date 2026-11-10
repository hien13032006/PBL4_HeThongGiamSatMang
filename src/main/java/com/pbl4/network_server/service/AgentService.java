package com.pbl4.network_server.service;

import com.pbl4.network_server.entity.Agent;
import com.pbl4.network_server.entity.MetricsHistory;
import com.pbl4.network_server.repository.AgentRepository;
import com.pbl4.network_server.repository.MetricsHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AgentService {

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private MetricsHistoryRepository metricsHistoryRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // 1. Phương thức đánh dấu Agent OFFLINE khi ngắt kết nối TCP
    @Transactional
    public void markAgentOffline(String agentId) {
        Optional<Agent> optionalAgent = agentRepository.findById(agentId);
        if (optionalAgent.isPresent()) {
            Agent agent = optionalAgent.get();
            if ("ONLINE".equals(agent.getStatus())) {
                agent.setStatus("OFFLINE");
                agentRepository.save(agent);
                System.out.println("[OFFLINE] Agent " + agentId + " ngắt kết nối TCP.");

                // Bắn WebSocket thông báo để Frontend tự động cập nhật
                messagingTemplate.convertAndSend("/topic/agents", agentRepository.findAll());
            }
        }
    }

    // 2. Lấy danh sách tất cả Agent cho Controller
    public List<Agent> getAllAgents() {
        return agentRepository.findAll();
    }

    // 3. Lấy 20 bản ghi metrics gần nhất cho Controller
    public List<MetricsHistory> getLatestMetrics(String agentId) {
        return metricsHistoryRepository.findTop20ByAgentAgentIdOrderByTimestampDesc(agentId);
    }

    // 4. Lấy thông tin tổng quan summary (Dung lượng tích lũy & % Tải) cho Controller
    public Map<String, Object> getAgentSummary(String agentId) {
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return null;
        }

        MetricsHistory latestMetrics = metricsHistoryRepository
                .findTopByAgentAgentIdOrderByTimestampDesc(agentId)
                .orElse(null);

        Double totalRxMB = metricsHistoryRepository.sumTotalRxByAgentId(agentId);
        Double totalTxMB = metricsHistoryRepository.sumTotalTxByAgentId(agentId);

        double totalRxGB = (totalRxMB != null) ? (totalRxMB / 1024.0) : 0.0;
        double totalTxGB = (totalTxMB != null) ? (totalTxMB / 1024.0) : 0.0;

        totalRxGB = Math.round(totalRxGB * 100.0) / 100.0;
        totalTxGB = Math.round(totalTxGB * 100.0) / 100.0;

        double currentUsagePercent = 0.0;
        double maxSpeedMb = (agent.getMaxSpeed() != null && agent.getMaxSpeed() > 0) ? agent.getMaxSpeed() : 125.0;

        if (latestMetrics != null) {
            double rxRate = (latestMetrics.getRxRate() != null) ? latestMetrics.getRxRate() : 0.0;
            double txRate = (latestMetrics.getTxRate() != null) ? latestMetrics.getTxRate() : 0.0;
            double totalTraffic = rxRate + txRate;

            currentUsagePercent = (totalTraffic / maxSpeedMb) * 100.0;
            if (currentUsagePercent > 100.0) currentUsagePercent = 100.0;
            currentUsagePercent = Math.round(currentUsagePercent * 100.0) / 100.0;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("agent", agent);
        response.put("latestMetrics", latestMetrics);
        response.put("totalRxGB", totalRxGB);
        response.put("totalTxGB", totalTxGB);
        response.put("maxSpeedMBps", maxSpeedMb);
        response.put("networkUsagePercent", currentUsagePercent);

        return response;
    }

    // 5. Tra cứu lịch sử Metrics theo khoảng thời gian cho Controller
    public Map<String, Object> getMetricsHistory(String agentId, LocalDateTime start, LocalDateTime end) {
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return null;
        }

        List<MetricsHistory> historyList = metricsHistoryRepository
                .findByAgentAgentIdAndTimestampBetweenOrderByTimestampAsc(agentId, start, end);

        double maxSpeedMb = (agent.getMaxSpeed() != null && agent.getMaxSpeed() > 0) ? agent.getMaxSpeed() : 125.0;

        Map<String, Object> response = new HashMap<>();
        response.put("agentId", agentId);
        response.put("hostname", agent.getHostname());
        response.put("maxSpeedMBps", maxSpeedMb);
        response.put("totalRecords", historyList.size());
        response.put("data", historyList);

        return response;
    }
    // 6. Xóa Agent và tất cả dữ liệu liên quan cho Controller
    @Transactional
    public boolean deleteAgent(String agentId) {
        if (agentRepository.existsById(agentId)) {
            // 1. Xóa tất cả chỉ số lịch sử liên quan đến Agent trước để tránh lỗi Foreign Key
            metricsHistoryRepository.deleteByAgentAgentId(agentId);

            // 2. Xóa Agent khỏi CSDL
            agentRepository.deleteById(agentId);

            // 3. Bắn WebSocket thông báo để các client cập nhật lại danh sách Agent
            try {
                messagingTemplate.convertAndSend("/topic/agents", agentRepository.findAll());
            } catch (Exception e) {
                System.err.println("Lỗi gửi WebSocket khi xóa Agent: " + e.getMessage());
            }
            return true;
        }
        return false;
    }
}
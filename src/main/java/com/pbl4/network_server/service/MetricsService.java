package com.pbl4.network_server.service;

import com.pbl4.network_server.dto.MetricsMessageDto;
import com.pbl4.network_server.entity.Agent;
import com.pbl4.network_server.entity.MetricsHistory;
import com.pbl4.network_server.repository.AgentRepository;
import com.pbl4.network_server.repository.MetricsHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/*Chịu trách nhiệm nhận dữ liệu từ Consumer Worker thực hiện việc cập nhật thông tin Agent
nếu là gói tin Register, lưu lịch sử metrics vào CSDL thông qua Repository và kiểm tra các ngưỡng cảnh báo*/

@Service
public class MetricsService {

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private MetricsHistoryRepository metricsHistoryRepository;
    
    @Autowired
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    @Transactional
    public void processAndSaveMetrics(MetricsMessageDto message) {
        if (message == null || message.getAgentId() == null) {
            return;
        }

        String agentId = message.getAgentId();
        String type = message.getType();

        // 1. Xử lý gói tin đăng ký (REGISTER) hoặc tự động đăng ký nếu agent chưa tồn tại
        Optional<Agent> optionalAgent = agentRepository.findById(agentId);
        Agent agent;

        if (optionalAgent.isPresent()) {
            agent = optionalAgent.get();
            agent.setStatus("ONLINE");
            agent.setLastSeen(LocalDateTime.now());
            // Nếu có thông tin metadata mới thì cập nhật lại
            if ("REGISTER".equalsIgnoreCase(type)) {
                if (message.getHostname() != null) agent.setHostname(message.getHostname());
                if (message.getIpAddress() != null) agent.setIpAddress(message.getIpAddress());
                if (message.getMacAddress() != null) agent.setMacAddress(message.getMacAddress());
                if (message.getOs() != null) agent.setOs(message.getOs());
                if (message.getNicName() != null) agent.setNicName(message.getNicName());
            }
            agentRepository.save(agent);
        } else {
            // Nếu Agent mới hoàn toàn gửi tin lên mà chưa có trong DB
            agent = Agent.builder()
                    .agentId(agentId)
                    .hostname(message.getHostname() != null ? message.getHostname() : "Unknown")
                    .ipAddress(message.getIpAddress() != null ? message.getIpAddress() : "127.0.0.1")
                    .macAddress(message.getMacAddress() != null ? message.getMacAddress() : "00:00:00:00:00:00")
                    .os(message.getOs())
                    .nicName(message.getNicName())
                    .status("ONLINE")
                    .lastSeen(LocalDateTime.now())
                    .build();
            agentRepository.save(agent);
        }

        // 2. Nếu message là loại METRICS, tiến hành lưu lịch sử vào bảng metrics_history
        if ("METRICS".equalsIgnoreCase(type) && message.getMetrics() != null) {
            MetricsMessageDto.MetricsDetailDto detail = message.getMetrics();

            // Chuyển đổi timestamp (Long) sang LocalDateTime
            LocalDateTime logTime;
            if (message.getTimestamp() != null) {
                logTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(message.getTimestamp()), ZoneId.systemDefault());
            } else {
                logTime = LocalDateTime.now();
            }

            MetricsHistory history = MetricsHistory.builder()
                    .agent(agent)
                    .timestamp(logTime)
                    .rxRate(detail.getRxRate())
                    .txRate(detail.getTxRate())
                    .rxPacketRate(detail.getRxPacketRate())
                    .txPacketRate(detail.getTxPacketRate())
                    .latency(detail.getLatency())
                    .packetLoss(detail.getPacketLoss())
                    .build();

            metricsHistoryRepository.save(history);

            // 3. Kiểm tra ngưỡng cảnh báo (Alert Engine cơ bản)
            checkThresholds(agentId, detail);

            messagingTemplate.convertAndSend("/topic/metrics/" + agentId, message);
            messagingTemplate.convertAndSend("/topic/agents", agent); // Cập nhật danh sách Agent
        }
    }

    // Phương thức kiểm tra các thông số có vượt ngưỡng cho phép không
    private void checkThresholds(String agentId, MetricsMessageDto.MetricsDetailDto detail) {
        if (detail.getLatency() != null && detail.getLatency() > 100.0) {
            System.out.println("[CANH BAO] Agent " + agentId + " co do tre cao: " + detail.getLatency() + " ms!");
        }
        if (detail.getPacketLoss() != null && detail.getPacketLoss() > 5.0) {
            System.out.println("[CANH BAO] Agent " + agentId + " ty le mat goi cao: " + detail.getPacketLoss() + "%!");
        }
    }
}
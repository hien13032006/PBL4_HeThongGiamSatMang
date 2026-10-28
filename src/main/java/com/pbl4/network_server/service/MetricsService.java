package com.pbl4.network_server.service;

import com.pbl4.network_server.dto.MetricsMessageDto;
import com.pbl4.network_server.entity.Agent;
import com.pbl4.network_server.entity.MetricsHistory;
import com.pbl4.network_server.repository.AgentRepository;
import com.pbl4.network_server.repository.MetricsHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/* Chịu trách nhiệm nhận dữ liệu từ Consumer Worker thực hiện việc cập nhật thông tin Agent
   nếu là gói tin Register, lưu lịch sử metrics vào CSDL thông qua Repository và kiểm tra các ngưỡng cảnh báo */

@Service
public class MetricsService {

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private MetricsHistoryRepository metricsHistoryRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Transactional
    public void processAndSaveMetrics(MetricsMessageDto message) {
        if (message == null || message.getAgentId() == null) {
            return;
        }

        String agentId = message.getAgentId();
        String type = message.getType();

        //Xử lý gói tin đăng ký (REGISTER) hoặc tự động tạo nếu chưa tồn tại
        Optional<Agent> optionalAgent = agentRepository.findById(agentId);
        Agent agent;
        boolean isNewAgent = false;
        boolean statusChangedToOnline = false;

        if (optionalAgent.isPresent()) {
            agent = optionalAgent.get();
            
            // Nếu Agent đang ở trạng thái OFFLINE mà gửi tin lên -> Chuyển lại ONLINE
            if (!"ONLINE".equalsIgnoreCase(agent.getStatus())) {
                agent.setStatus("ONLINE");
                statusChangedToOnline = true;
            }
            agent.setLastSeen(LocalDateTime.now());
            
            if ("REGISTER".equalsIgnoreCase(type)) {
                if (message.getHostname() != null) agent.setHostname(message.getHostname());
                if (message.getIpAddress() != null) agent.setIpAddress(message.getIpAddress());
                if (message.getMacAddress() != null) agent.setMacAddress(message.getMacAddress());
                if (message.getOs() != null) agent.setOs(message.getOs());
                if (message.getNicName() != null) agent.setNicName(message.getNicName());
            }
            agentRepository.save(agent);
        } else {
            // Trường hợp Agent mới hoàn toàn gửi tin lên
            agent = Agent.builder()
                    .agentId(agentId)
                    .hostname(message.getHostname() != null ? message.getHostname() : "Unknown")
                    .ipAddress(message.getIpAddress() != null ? message.getIpAddress() : "127.0.0.1")
                    .macAddress(message.getMacAddress() != null ? message.getMacAddress() : "00:00:00:00:00:00")
                    .os(message.getOs() != null ? message.getOs() : "Unknown")
                    .nicName(message.getNicName())
                    .status("ONLINE")
                    .lastSeen(LocalDateTime.now())
                    .build();
            agentRepository.save(agent);
            isNewAgent = true;
        }

        // Chỉ bắn WebSocket `/topic/agents` khi có Agent mới hoặc Agent chuyển trạng thái từ OFFLINE -> ONLINE
        if ("REGISTER".equalsIgnoreCase(type) || isNewAgent || statusChangedToOnline) {
            messagingTemplate.convertAndSend("/topic/agents", agentRepository.findAll());
        }

        // Nếu message là loại METRICS, tiến hành lưu lịch sử vào CSDL
        // Trong file MetricsService.java

if ("METRICS".equalsIgnoreCase(type) && message.getMetrics() != null) {
    MetricsMessageDto.MetricsDetailDto detail = message.getMetrics();

    // 1. QUY ĐỔI TỪ KB/s SANG MB/s (Chia cho 1024.0)
    double rxRateMb = detail.getRxRate() != null ? (detail.getRxRate() / 1024.0) : 0.0;
    double txRateMb = detail.getTxRate() != null ? (detail.getTxRate() / 1024.0) : 0.0;

    // Cập nhật lại giá trị đã đổi vào DTO để bắn qua WebSocket cho Dashboard chuẩn MB/s
    detail.setRxRate(rxRateMb);
    detail.setTxRate(txRateMb);

    // 2. Chuyển đổi timestamp
    LocalDateTime logTime;
    if (message.getTimestamp() != null) {
        logTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(message.getTimestamp()), ZoneId.systemDefault());
    } else {
        logTime = LocalDateTime.now();
    }

    // 3. Lưu vào bảng metrics_history (Đã ở đơn vị MB/s)
    MetricsHistory history = MetricsHistory.builder()
            .agent(agent)
            .timestamp(logTime)
            .rxRate(rxRateMb)
            .txRate(txRateMb)
            .rxPacketRate(detail.getRxPacketRate())
            .txPacketRate(detail.getTxPacketRate())
            .latency(detail.getLatency())
            .packetLoss(detail.getPacketLoss())
            .build();

    metricsHistoryRepository.save(history);

    // 4. Kiểm tra ngưỡng cảnh báo (Sử dụng ngưỡng MB/s)
    checkThresholds(agentId, detail);

    // 5. Bắn dữ liệu real-time xuống WebSocket (Đã quy đổi MB/s)
    messagingTemplate.convertAndSend("/topic/metrics/" + agentId, message);
}
    }

    private void checkThresholds(String agentId, MetricsMessageDto.MetricsDetailDto detail) {
        if (detail.getLatency() != null && detail.getLatency() > 100.0) {
            System.out.println("[CANH BAO] Agent " + agentId + " co do tre cao: " + detail.getLatency() + " ms!");
        }
        if (detail.getPacketLoss() != null && detail.getPacketLoss() > 5.0) {
            System.out.println("[CANH BAO] Agent " + agentId + " ty le mat goi cao: " + detail.getPacketLoss() + "%!");
        }
    }
}
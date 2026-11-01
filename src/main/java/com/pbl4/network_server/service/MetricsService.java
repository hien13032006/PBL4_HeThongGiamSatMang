package com.pbl4.network_server.service;

import com.pbl4.network_server.dto.MetricsMessageDto;
import com.pbl4.network_server.entity.Agent;
import com.pbl4.network_server.entity.MetricsHistory;
import com.pbl4.network_server.repository.AgentRepository;
import com.pbl4.network_server.repository.MetricsHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private MetricsHistoryRepository metricsHistoryRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // Bộ nhớ đệm lưu trữ danh sách metrics gần nhất của từng Agent để tính toán chuỗi thời gian (Sliding Window)
    private final Map<String, LinkedList<MetricsMessageDto.MetricsDetailDto>> agentWindowMap = new ConcurrentHashMap<>();
    private static final int WINDOW_LIMIT = 300; // Lưu tối đa 300 mẫu (tương đương 5 phút nếu 1s/mẫu)

    @Transactional
    public void processAndSaveMetrics(MetricsMessageDto message) {
        if (message == null || message.getAgentId() == null) {
            return;
        }

        String agentId = message.getAgentId();
        String type = message.getType();

        Optional<Agent> optionalAgent = agentRepository.findById(agentId);
        Agent agent;
        boolean isNewAgent = false;
        boolean statusChangedToOnline = false;

        // Lấy & Quy đổi maxSpeed (Mbps -> MB/s)
        Double rawMaxSpeedMbps = message.getMaxSpeed();
        if (rawMaxSpeedMbps == null && message.getMetrics() != null) {
            rawMaxSpeedMbps = message.getMetrics().getMaxSpeed();
        }

        Double convertedMaxSpeedMb = null;
        if (rawMaxSpeedMbps != null && rawMaxSpeedMbps > 0) {
            convertedMaxSpeedMb = rawMaxSpeedMbps / 8.0;
        }

        if (optionalAgent.isPresent()) {
            agent = optionalAgent.get();
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

            if (convertedMaxSpeedMb != null && convertedMaxSpeedMb > 0) {
                agent.setMaxSpeed(convertedMaxSpeedMb);
            } else if (agent.getMaxSpeed() == null) {
                agent.setMaxSpeed(125.0); // Mặc định 1 Gbps = 125 MB/s
            }
            agentRepository.save(agent);
        } else {
            agent = Agent.builder()
                    .agentId(agentId)
                    .hostname(message.getHostname() != null ? message.getHostname() : "Unknown")
                    .ipAddress(message.getIpAddress() != null ? message.getIpAddress() : "127.0.0.1")
                    .macAddress(message.getMacAddress() != null ? message.getMacAddress() : "00:00:00:00:00:00")
                    .os(message.getOs() != null ? message.getOs() : "Unknown")
                    .nicName(message.getNicName())
                    .maxSpeed(convertedMaxSpeedMb != null && convertedMaxSpeedMb > 0 ? convertedMaxSpeedMb : 125.0)
                    .status("ONLINE")
                    .lastSeen(LocalDateTime.now())
                    .build();
            agentRepository.save(agent);
            isNewAgent = true;
        }

        message.setAgent(agent);

        if ("REGISTER".equalsIgnoreCase(type) || isNewAgent || statusChangedToOnline) {
            messagingTemplate.convertAndSend("/topic/agents", agentRepository.findAll());
        }

        if ("METRICS".equalsIgnoreCase(type) && message.getMetrics() != null) {
            MetricsMessageDto.MetricsDetailDto detail = message.getMetrics();

            // Đổi KB/s -> MB/s
            double rxRateMb = detail.getRxRate() != null ? (detail.getRxRate() / 1024.0) : 0.0;
            double txRateMb = detail.getTxRate() != null ? (detail.getTxRate() / 1024.0) : 0.0;

            detail.setRxRate(rxRateMb);
            detail.setTxRate(txRateMb);

            double currentMaxSpeedMb = (agent.getMaxSpeed() != null && agent.getMaxSpeed() > 0) ? agent.getMaxSpeed() : 125.0;
            detail.setMaxSpeed(currentMaxSpeedMb);

            double totalTrafficMb = rxRateMb + txRateMb;
            double usagePercent = (totalTrafficMb / currentMaxSpeedMb) * 100.0;
            if (usagePercent > 100.0) usagePercent = 100.0;
            detail.setNetworkUsagePercent(Math.round(usagePercent * 100.0) / 100.0);

            LocalDateTime logTime;
            if (message.getTimestamp() != null) {
                logTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(message.getTimestamp()), ZoneId.systemDefault());
            } else {
                logTime = LocalDateTime.now();
            }

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

            // Đẩy mẫu dữ liệu vào Sliding Window
            agentWindowMap.putIfAbsent(agentId, new LinkedList<>());
            LinkedList<MetricsMessageDto.MetricsDetailDto> window = agentWindowMap.get(agentId);
            synchronized (window) {
                window.addLast(detail);
                if (window.size() > WINDOW_LIMIT) {
                    window.removeFirst();
                }
            }

            // Thực hiện kiểm tra toàn bộ tiêu chí cảnh báo nâng cao
            evaluateStrictThresholds(agentId, agent.getHostname(), window, currentMaxSpeedMb);

            messagingTemplate.convertAndSend("/topic/metrics/" + agentId, message);
        }
    }

    /**
     * Engine kiểm tra các quy tắc cảnh báo theo thời gian thực (Packet Loss, Latency, RX/TX, Anomaly)
     */
    private void evaluateStrictThresholds(String agentId, String hostname, 
                                           LinkedList<MetricsMessageDto.MetricsDetailDto> window, 
                                           double maxSpeedMb) {
        String host = (hostname != null) ? hostname : agentId;
        List<MetricsMessageDto.MetricsDetailDto> samples;

        synchronized (window) {
            samples = new ArrayList<>(window);
        }

        if (samples.isEmpty()) return;
        MetricsMessageDto.MetricsDetailDto latest = samples.get(samples.size() - 1);

        // --------------------------------------------------------------------------
        // 1. NGƯỠNG PACKET LOSS
        // --------------------------------------------------------------------------
        if (latest.getPacketLoss() != null) {
            // Critical: Packet Loss > 10% ngay lập tức
            if (latest.getPacketLoss() > 10.0) {
                log.error("[CRITICAL - PACKET LOSS] Agent {} ({}) bị mất gói tức thời cực cao: {}%!", host, agentId, latest.getPacketLoss());
            } else {
                // Critical: Loss > 5% trong vòng 1 phút (60 mẫu)
                double avgLoss1Min = getAveragePacketLoss(samples, 60);
                if (avgLoss1Min > 5.0) {
                    log.error("[CRITICAL - PACKET LOSS] Agent {} ({}) mất gói trung bình 1 phút cao: {}%!", host, agentId, String.format("%.1f", avgLoss1Min));
                } 
                // Warning: Loss > 1% trong vòng 2 phút (120 mẫu)
                else {
                    double avgLoss2Min = getAveragePacketLoss(samples, 120);
                    if (avgLoss2Min > 1.0) {
                        log.warn("[WARNING - PACKET LOSS] Agent {} ({}) đường truyền suy hao (Loss 2 min): {}%", host, agentId, String.format("%.1f", avgLoss2Min));
                    }
                }
            }
        }

        // --------------------------------------------------------------------------
        // 2. NGƯỠNG LATENCY (Internet Server)
        // --------------------------------------------------------------------------
        if (latest.getLatency() != null) {
            // Critical: Ping > 150ms trong 2 phút (120 mẫu)
            double avgLatency2Min = getAverageLatency(samples, 120);
            if (avgLatency2Min > 150.0) {
                log.error("[CRITICAL - LATENCY] Agent {} ({}) Ping Internet trung bình 2 phút rất cao: {} ms!", host, agentId, String.format("%.1f", avgLatency2Min));
            } 
            // Warning: Ping > 80ms trong 3 phút (180 mẫu)
            else {
                double avgLatency3Min = getAverageLatency(samples, 180);
                if (avgLatency3Min > 80.0) {
                    log.warn("[WARNING - LATENCY] Agent {} ({}) Ping Internet có dấu hiệu trễ (3 min): {} ms", host, agentId, String.format("%.1f", avgLatency3Min));
                }
            }
        }

        // --------------------------------------------------------------------------
        // 3. NGƯỠNG BĂNG THÔNG (RX / TX UTILIZATION & ANOMALY)
        // --------------------------------------------------------------------------
        if (latest.getNetworkUsagePercent() != null) {
            // Critical: RX hoặc TX > 95% kéo dài liên tục trên 3 phút (180 mẫu)
            double avgUsage3Min = getAverageUsagePercent(samples, 180);
            if (avgUsage3Min > 95.0) {
                log.error("[CRITICAL - BANDWIDTH] Agent {} ({}) cạn kiệt tài nguyên mạng (Sử dụng 3 min): {}%!", host, agentId, String.format("%.1f", avgUsage3Min));
            } 
            // Warning: RX hoặc TX > 80% kéo dài liên tục trên 5 phút (300 mẫu)
            else {
                double avgUsage5Min = getAverageUsagePercent(samples, 300);
                if (avgUsage5Min > 80.0) {
                    log.warn("[WARNING - BANDWIDTH] Agent {} ({}) quá tải băng thông kéo dài 5 phút: {}%", host, agentId, String.format("%.1f", avgUsage5Min));
                }
            }
        }

        // Cảnh báo Bất thường (Anomaly/Security - Upload đán cắp dữ liệu/Botnet): TX > 15 Mbps (1.875 MB/s) trong hơn 10 phút
        // Ở đây lấy mẫu kiểm tra trong chu kỳ 5 phút window gần nhất
        double avgTxRateMb5Min = getAverageTxRate(samples, 300);
        double txRateThresholdMb = 15.0 / 8.0; // 15 Mbps = 1.875 MB/s
        if (avgTxRateMb5Min > txRateThresholdMb) {
            log.error("[SECURITY ALERT] Phát hiện bất thường Upload lớn liên tục ở Agent {} ({}): {} MB/s (~{} Mbps)!", 
                    host, agentId, String.format("%.2f", avgTxRateMb5Min), String.format("%.1f", avgTxRateMb5Min * 8));
        }
    }

    // Các hàm phụ trợ tính trung bình mẫu (Sliding Window Helper Methods)
    private double getAveragePacketLoss(List<MetricsMessageDto.MetricsDetailDto> samples, int count) {
        int limit = Math.min(samples.size(), count);
        if (limit == 0) return 0.0;
        double sum = 0;
        for (int i = samples.size() - limit; i < samples.size(); i++) {
            Double val = samples.get(i).getPacketLoss();
            sum += (val != null) ? val : 0.0;
        }
        return sum / limit;
    }

    private double getAverageLatency(List<MetricsMessageDto.MetricsDetailDto> samples, int count) {
        int limit = Math.min(samples.size(), count);
        if (limit == 0) return 0.0;
        double sum = 0;
        for (int i = samples.size() - limit; i < samples.size(); i++) {
            Double val = samples.get(i).getLatency();
            sum += (val != null) ? val : 0.0;
        }
        return sum / limit;
    }

    private double getAverageUsagePercent(List<MetricsMessageDto.MetricsDetailDto> samples, int count) {
        int limit = Math.min(samples.size(), count);
        if (limit == 0) return 0.0;
        double sum = 0;
        for (int i = samples.size() - limit; i < samples.size(); i++) {
            Double val = samples.get(i).getNetworkUsagePercent();
            sum += (val != null) ? val : 0.0;
        }
        return sum / limit;
    }

    private double getAverageTxRate(List<MetricsMessageDto.MetricsDetailDto> samples, int count) {
        int limit = Math.min(samples.size(), count);
        if (limit == 0) return 0.0;
        double sum = 0;
        for (int i = samples.size() - limit; i < samples.size(); i++) {
            Double val = samples.get(i).getTxRate();
            sum += (val != null) ? val : 0.0;
        }
        return sum / limit;
    }
}
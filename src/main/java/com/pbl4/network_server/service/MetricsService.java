package com.pbl4.network_server.service;

import com.pbl4.network_server.dto.MetricsMessageDto;
import com.pbl4.network_server.entity.Agent;
import com.pbl4.network_server.entity.AlertThreshold;
import com.pbl4.network_server.entity.MetricsHistory;
import com.pbl4.network_server.repository.AgentRepository;
import com.pbl4.network_server.repository.MetricsHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private MetricsHistoryRepository metricsHistoryRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private AlertService alertService;

    // Quản lý Sliding Window riêng biệt 100% cho từng Agent
    private final Map<String, Queue<MetricsMessageDto.MetricsDetailDto>> agentWindowMap = new ConcurrentHashMap<>();
    private static final int MAX_SLIDING_SAMPLES = 60; // Tối đa 60 mẫu gần nhất (~1-2 phút)

    private final Map<String, Long> lastAlertTimeMap = new ConcurrentHashMap<>();

    private boolean shouldTriggerAlert(String agentId, String alertKey, Integer durationMinutes) {
        String key = agentId + "_" + alertKey;
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastAlertTimeMap.get(key);

        long cooldownMs = (durationMinutes != null && durationMinutes > 0) ? (long) durationMinutes * 60 * 1000 : 30000;

        if (lastTime == null || (currentTime - lastTime) >= cooldownMs) {
            lastAlertTimeMap.put(key, currentTime);
            return true;
        }
        return false;
    }

    public void processAndSaveMetrics(MetricsMessageDto message) {
        if (message == null || message.getAgentId() == null) return;

        String agentId = message.getAgentId();
        String type = message.getType();

        Optional<Agent> optionalAgent = agentRepository.findById(agentId);
        Agent agent;
        boolean isNewAgent = false;
        boolean statusChangedToOnline = false;

        Double rawMaxSpeedMbps = message.getMaxSpeed();
        if (rawMaxSpeedMbps == null && message.getMetrics() != null) {
            rawMaxSpeedMbps = message.getMetrics().getMaxSpeed();
        }

        Double convertedMaxSpeedMb = (rawMaxSpeedMbps != null && rawMaxSpeedMbps > 0) ? rawMaxSpeedMbps / 8.0 : null;

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
                agent.setMaxSpeed(125.0);
            }
            
            // Chỉ save agent vào CSDL khi ĐĂNG KÝ hoặc THAY ĐỔI TRẠNG THÁI để tránh quá tải MySQL
            if ("REGISTER".equalsIgnoreCase(type) || statusChangedToOnline) {
                agentRepository.save(agent);
            }
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
                    .qualityStatus("NORMAL")
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

            // LUÔN DÙNG THỜI GIAN THỰC CỦA SERVER để đồng bộ mốc thời gian giữa các Container
            LocalDateTime logTime = LocalDateTime.now();

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

            // Đẩy mẫu dữ liệu vào Queue cửa sổ trượt riêng của Agent này
            Queue<MetricsMessageDto.MetricsDetailDto> window = agentWindowMap.computeIfAbsent(agentId, k -> new ConcurrentLinkedQueue<>());
            window.add(detail);
            while (window.size() > MAX_SLIDING_SAMPLES) {
                window.poll();
            }

            evaluateStrictThresholds(agent, new ArrayList<>(window), currentMaxSpeedMb);

            messagingTemplate.convertAndSend("/topic/metrics/" + agentId, message);
        }
    }

    private void evaluateStrictThresholds(Agent agent, List<MetricsMessageDto.MetricsDetailDto> samples, double maxSpeedMb) {
        if (samples.isEmpty()) return;

        String agentId = agent.getAgentId();
        String host = (agent.getHostname() != null) ? agent.getHostname() : agentId;
        MetricsMessageDto.MetricsDetailDto latest = samples.get(samples.size() - 1);

        AlertThreshold cfg = alertService.getThresholds();
        if (cfg == null) return;

        // Đánh giá cấp độ nguy hiểm độc lập cho từng chỉ số
        boolean isCritical = false;
        boolean isWarning = false;

        // 1. KIỂM TRA PACKET RATE ANOMALY (Phát hiện tấn công)
        if (checkPacketRateAnomalyAlert(agentId, host, latest)) {
            isCritical = true;
        }

        // 2. NGƯỠNG PACKET LOSS
        if (latest.getPacketLoss() != null) {
            Double lossImmediateCfg = cfg.getLossCritImmediate();
            if (lossImmediateCfg != null && latest.getPacketLoss() > lossImmediateCfg) {
                isCritical = true;
                if (shouldTriggerAlert(agentId, "LOSS_IMMEDIATE", 0)) {
                    alertService.addAlertLog(agentId, host, "Packet Loss", "CRITICAL", latest.getPacketLoss(), "%", lossImmediateCfg);
                }
            } else {
                double avgLoss = getAveragePacketLoss(samples);
                Double lossCritCfg = cfg.getLossCritPercent();
                Double lossWarnCfg = cfg.getLossWarnPercent();

                if (lossCritCfg != null && avgLoss > lossCritCfg) {
                    isCritical = true;
                    if (shouldTriggerAlert(agentId, "LOSS_CRITICAL", cfg.getLossCritDuration())) {
                        alertService.addAlertLog(agentId, host, "Packet Loss", "CRITICAL", avgLoss, "%", lossCritCfg);
                    }
                } else if (lossWarnCfg != null && avgLoss > lossWarnCfg) {
                    isWarning = true;
                    if (shouldTriggerAlert(agentId, "LOSS_WARNING", cfg.getLossWarnDuration())) {
                        alertService.addAlertLog(agentId, host, "Packet Loss", "WARNING", avgLoss, "%", lossWarnCfg);
                    }
                }
            }
        }

        // 3. NGƯỠNG LATENCY
        if (latest.getLatency() != null) {
            double avgLatency = getAverageLatency(samples);
            Double latencyCritCfg = cfg.getLatencyCritMs();
            Double latencyWarnCfg = cfg.getLatencyWarnMs();

            if (latencyCritCfg != null && avgLatency > latencyCritCfg) {
                isCritical = true;
                if (shouldTriggerAlert(agentId, "LATENCY_CRITICAL", cfg.getLatencyCritDuration())) {
                    alertService.addAlertLog(agentId, host, "Latency", "CRITICAL", avgLatency, "ms", latencyCritCfg);
                }
            } else if (latencyWarnCfg != null && avgLatency > latencyWarnCfg) {
                isWarning = true;
                if (shouldTriggerAlert(agentId, "LATENCY_WARNING", cfg.getLatencyWarnDuration())) {
                    alertService.addAlertLog(agentId, host, "Latency", "WARNING", avgLatency, "ms", latencyWarnCfg);
                }
            }
        }

        // 4. NGƯỠNG BĂNG THÔNG
        if (latest.getNetworkUsagePercent() != null) {
            double avgUsage = getAverageUsagePercent(samples);
            Double bwCritCfg = cfg.getBwCritPercent();
            Double bwWarnCfg = cfg.getBwWarnPercent();

            if (bwCritCfg != null && avgUsage > bwCritCfg) {
                isCritical = true;
                if (shouldTriggerAlert(agentId, "BW_CRITICAL", cfg.getBwCritDuration())) {
                    alertService.addAlertLog(agentId, host, "Bandwidth", "CRITICAL", avgUsage, "%", bwCritCfg);
                }
            } else if (bwWarnCfg != null && avgUsage > bwWarnCfg) {
                isWarning = true;
                if (shouldTriggerAlert(agentId, "BW_WARNING", cfg.getBwWarnDuration())) {
                    alertService.addAlertLog(agentId, host, "Bandwidth", "WARNING", avgUsage, "%", bwWarnCfg);
                }
            }
        }

        // 5. TỔNG HỢP TRẠNG THÁI CUỐI CÙNG (Thứ tự ưu tiên: CRITICAL > WARNING > NORMAL)
        String calculatedQuality = "NORMAL";
        if (isCritical) {
            calculatedQuality = "CRITICAL";
        } else if (isWarning) {
            calculatedQuality = "WARNING";
        }

        // 6. CẬP NHẬT CSDl VÀ PUSH WEBSOCKET CHỈ KHI TRẠNG THÁI THAY ĐỔI
        if (!calculatedQuality.equalsIgnoreCase(agent.getQualityStatus())) {
            agent.setQualityStatus(calculatedQuality);
            agentRepository.save(agent);
            messagingTemplate.convertAndSend("/topic/agents", agentRepository.findAll());
        }
    }

    private boolean checkPacketRateAnomalyAlert(String agentId, String host, MetricsMessageDto.MetricsDetailDto latest) {
        Long rxPps = latest.getRxPacketRate() != null ? latest.getRxPacketRate() : 0L;
        Long txPps = latest.getTxPacketRate() != null ? latest.getTxPacketRate() : 0L;
        long totalPps = rxPps + txPps;
        double PACKET_RATE_CRIT_THRESHOLD = 30000.0;

        if (totalPps > PACKET_RATE_CRIT_THRESHOLD) {
            if (shouldTriggerAlert(agentId, "PACKET_RATE_ANOMALY", 0)) {
                log.error("[CRITICAL - PACKET RATE ANOMALY] Agent {} ({}) tốc độ gói tin bất thường: {} pps!", host, agentId, totalPps);
                alertService.addAlertLog(agentId, host, "Packet Rate Anomaly", "CRITICAL", (double) totalPps, "pps", PACKET_RATE_CRIT_THRESHOLD);
            }
            return true;
        }
        return false;
    }

    private double getAveragePacketLoss(List<MetricsMessageDto.MetricsDetailDto> samples) {
        return samples.stream().mapToDouble(s -> s.getPacketLoss() != null ? s.getPacketLoss() : 0.0).average().orElse(0.0);
    }

    private double getAverageLatency(List<MetricsMessageDto.MetricsDetailDto> samples) {
        return samples.stream().mapToDouble(s -> s.getLatency() != null ? s.getLatency() : 0.0).average().orElse(0.0);
    }

    private double getAverageUsagePercent(List<MetricsMessageDto.MetricsDetailDto> samples) {
        return samples.stream().mapToDouble(s -> s.getNetworkUsagePercent() != null ? s.getNetworkUsagePercent() : 0.0).average().orElse(0.0);
    }
}
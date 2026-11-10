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

    @Autowired
    private AlertService alertService;

    private final Map<String, LinkedList<MetricsMessageDto.MetricsDetailDto>> agentWindowMap = new ConcurrentHashMap<>();
    private static final int WINDOW_LIMIT = 300; 

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
                agent.setMaxSpeed(125.0);
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

            agentWindowMap.putIfAbsent(agentId, new LinkedList<>());
            LinkedList<MetricsMessageDto.MetricsDetailDto> window = agentWindowMap.get(agentId);
            synchronized (window) {
                window.addLast(detail);
                if (window.size() > WINDOW_LIMIT) {
                    window.removeFirst();
                }
            }

            evaluateStrictThresholds(agentId, agent.getHostname(), window, currentMaxSpeedMb);

            messagingTemplate.convertAndSend("/topic/metrics/" + agentId, message);
        }
    }

    /**
     * Đánh giá cảnh báo và gắn ngay thuộc tính qualityStatus khi phát hiện vi phạm
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

        AlertThreshold cfg = alertService.getThresholds();
        if (cfg == null) return;

        String currentQuality = "NORMAL";

        // --------------------------------------------------------------------------
        // 0. KIỂM TRA PHÁT HIỆN XÂM NHẬP DỰA TRÊN PACKET RATE BẤT THƯỜNG
        // --------------------------------------------------------------------------
        if (checkPacketRateAnomalyAlert(agentId, host, latest)) {
            currentQuality = "CRITICAL";
        }

        // --------------------------------------------------------------------------
        // 1. NGƯỠNG PACKET LOSS
        // --------------------------------------------------------------------------
        if (latest.getPacketLoss() != null) {
            Double lossImmediateCfg = cfg.getLossCritImmediate();
            if (lossImmediateCfg != null && latest.getPacketLoss() > lossImmediateCfg) {
                currentQuality = "CRITICAL";
                if (shouldTriggerAlert(agentId, "LOSS_IMMEDIATE", 0)) {
                    log.error("[CRITICAL - PACKET LOSS] Agent {} ({}) bị mất gói tức thời: {}%!", host, agentId, latest.getPacketLoss());
                    alertService.addAlertLog(agentId, host, "Packet Loss", "CRITICAL", latest.getPacketLoss(), "%", lossImmediateCfg);
                }
            } else {
                Double lossCritCfg = cfg.getLossCritPercent();
                Integer lossCritMin = cfg.getLossCritDuration();
                if (lossCritCfg != null && lossCritMin != null) {
                    int lossCritSamples = lossCritMin * 60;
                    if (samples.size() >= Math.max(1, (int)(lossCritSamples * 0.8))) {
                        double avgLossCrit = getAveragePacketLoss(samples, lossCritSamples);
                        if (avgLossCrit > lossCritCfg) {
                            currentQuality = "CRITICAL";
                            if (shouldTriggerAlert(agentId, "LOSS_CRITICAL", lossCritMin)) {
                                log.error("[CRITICAL - PACKET LOSS] Agent {} ({}) mất gói trung bình: {}%!", host, agentId, String.format("%.1f", avgLossCrit));
                                alertService.addAlertLog(agentId, host, "Packet Loss", "CRITICAL", avgLossCrit, "%", lossCritCfg);
                            }
                        }
                    }
                }

                if (!"CRITICAL".equals(currentQuality)) {
                    Double lossWarnCfg = cfg.getLossWarnPercent();
                    Integer lossWarnMin = cfg.getLossWarnDuration();
                    if (lossWarnCfg != null && lossWarnMin != null) {
                        int lossWarnSamples = lossWarnMin * 60;
                        if (samples.size() >= Math.max(1, (int)(lossWarnSamples * 0.8))) {
                            double avgLossWarn = getAveragePacketLoss(samples, lossWarnSamples);
                            if (avgLossWarn > lossWarnCfg) {
                                currentQuality = "WARNING";
                                if (shouldTriggerAlert(agentId, "LOSS_WARNING", lossWarnMin)) {
                                    log.warn("[WARNING - PACKET LOSS] Agent {} ({}) mất gói trung bình: {}%", host, agentId, String.format("%.1f", avgLossWarn));
                                    alertService.addAlertLog(agentId, host, "Packet Loss", "WARNING", avgLossWarn, "%", lossWarnCfg);
                                }
                            }
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------------------------
        // 2. NGƯỠNG LATENCY
        // --------------------------------------------------------------------------
        if (latest.getLatency() != null) {
            Double latencyCritCfg = cfg.getLatencyCritMs();
            Integer latencyCritMin = cfg.getLatencyCritDuration();
            if (latencyCritCfg != null && latencyCritMin != null) {
                int latencyCritSamples = latencyCritMin * 60;
                if (samples.size() >= Math.max(1, (int)(latencyCritSamples * 0.8))) {
                    double avgLatencyCrit = getAverageLatency(samples, latencyCritSamples);
                    if (avgLatencyCrit > latencyCritCfg) {
                        currentQuality = "CRITICAL";
                        if (shouldTriggerAlert(agentId, "LATENCY_CRITICAL", latencyCritMin)) {
                            log.error("[CRITICAL - LATENCY] Agent {} ({}) Ping trung bình: {} ms!", host, agentId, String.format("%.1f", avgLatencyCrit));
                            alertService.addAlertLog(agentId, host, "Latency", "CRITICAL", avgLatencyCrit, "ms", latencyCritCfg);
                        }
                    }
                }
            }

            if (!"CRITICAL".equals(currentQuality)) {
                Double latencyWarnCfg = cfg.getLatencyWarnMs();
                Integer latencyWarnMin = cfg.getLatencyWarnDuration();
                if (latencyWarnCfg != null && latencyWarnMin != null) {
                    int latencyWarnSamples = latencyWarnMin * 60;
                    if (samples.size() >= Math.max(1, (int)(latencyWarnSamples * 0.8))) {
                        double avgLatencyWarn = getAverageLatency(samples, latencyWarnSamples);
                        if (avgLatencyWarn > latencyWarnCfg) {
                            if (!"CRITICAL".equals(currentQuality)) {
                                currentQuality = "WARNING";
                            }
                            if (shouldTriggerAlert(agentId, "LATENCY_WARNING", latencyWarnMin)) {
                                log.warn("[WARNING - LATENCY] Agent {} ({}) Ping trung bình: {} ms", host, agentId, String.format("%.1f", avgLatencyWarn));
                                alertService.addAlertLog(agentId, host, "Latency", "WARNING", avgLatencyWarn, "ms", latencyWarnCfg);
                            }
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------------------------
        // 3. NGƯỠNG BĂNG THÔNG (BANDWIDTH)
        // --------------------------------------------------------------------------
        if (latest.getNetworkUsagePercent() != null) {
            Double bwCritCfg = cfg.getBwCritPercent();
            Integer bwCritMin = cfg.getBwCritDuration();
            if (bwCritCfg != null && bwCritMin != null) {
                int bwCritSamples = bwCritMin * 60;
                if (samples.size() >= Math.max(1, (int)(bwCritSamples * 0.8))) {
                    double avgUsageCrit = getAverageUsagePercent(samples, bwCritSamples);
                    if (avgUsageCrit > bwCritCfg) {
                        currentQuality = "CRITICAL";
                        if (shouldTriggerAlert(agentId, "BW_CRITICAL", bwCritMin)) {
                            log.error("[CRITICAL - BANDWIDTH] Agent {} ({}) quá tải băng thông: {}%!", host, agentId, String.format("%.1f", avgUsageCrit));
                            alertService.addAlertLog(agentId, host, "Bandwidth", "CRITICAL", avgUsageCrit, "%", bwCritCfg);
                        }
                    }
                }
            }

            if (!"CRITICAL".equals(currentQuality)) {
                Double bwWarnCfg = cfg.getBwWarnPercent();
                Integer bwWarnMin = cfg.getBwWarnDuration();
                if (bwWarnCfg != null && bwWarnMin != null) {
                    int bwWarnSamples = bwWarnMin * 60;
                    if (samples.size() >= Math.max(1, (int)(bwWarnSamples * 0.8))) {
                        double avgUsageWarn = getAverageUsagePercent(samples, bwWarnSamples);
                        if (avgUsageWarn > bwWarnCfg) {
                            if (!"CRITICAL".equals(currentQuality)) {
                                currentQuality = "WARNING";
                            }
                            if (shouldTriggerAlert(agentId, "BW_WARNING", bwWarnMin)) {
                                log.warn("[WARNING - BANDWIDTH] Agent {} ({}) quá tải băng thông: {}%", host, agentId, String.format("%.1f", avgUsageWarn));
                                alertService.addAlertLog(agentId, host, "Bandwidth", "WARNING", avgUsageWarn, "%", bwWarnCfg);
                            }
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------------------------
        // CẬP NHẬT TRỰC TIẾP THUỘC TÍNH QUALITY_STATUS VÀO CSDL AGENTS
        // --------------------------------------------------------------------------
        final String finalQuality = currentQuality;
        agentRepository.findById(agentId).ifPresent(agent -> {
            if (!finalQuality.equalsIgnoreCase(agent.getQualityStatus())) {
                agent.setQualityStatus(finalQuality);
                agentRepository.save(agent); // Lưu trực tiếp xuống MySQL

                // Phát lại danh sách Agent mới nhất qua WebSocket cho trang Dashboard
                messagingTemplate.convertAndSend("/topic/agents", agentRepository.findAll());
            }
        });
    }

    /**
     * Kiểm tra hành vi xâm nhập dựa trên Packet Rate bất thường
     */
    private boolean checkPacketRateAnomalyAlert(String agentId, String host, MetricsMessageDto.MetricsDetailDto latest) {
        Long rxPps = latest.getRxPacketRate() != null ? latest.getRxPacketRate() : 0L;
        Long txPps = latest.getTxPacketRate() != null ? latest.getTxPacketRate() : 0L;
        long totalPps = rxPps + txPps;

        // Ngưỡng an toàn: > 30,000 pps nghi vấn bị tấn công Flood/Scanning
        double PACKET_RATE_CRIT_THRESHOLD = 30000.0;

        if (totalPps > PACKET_RATE_CRIT_THRESHOLD) {
            if (shouldTriggerAlert(agentId, "PACKET_RATE_ANOMALY", 0)) { // Bắn ngay không qua cooldown
                log.error("[CRITICAL - PACKET RATE ANOMALY] Agent {} ({}) tốc độ gói tin bất thường: {} pps (RX: {}, TX: {})!",
                        host, agentId, totalPps, rxPps, txPps);

                alertService.addAlertLog(
                    agentId,
                    host,
                    "Packet Rate Anomaly",
                    "CRITICAL",
                    (double) totalPps,
                    "pps",
                    PACKET_RATE_CRIT_THRESHOLD
                );
            }
            return true;
        }
        return false;
    }

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
}
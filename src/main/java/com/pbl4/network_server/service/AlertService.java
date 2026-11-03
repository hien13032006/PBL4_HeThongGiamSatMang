package com.pbl4.network_server.service;

import com.pbl4.network_server.entity.AlertLog;
import com.pbl4.network_server.entity.AlertThreshold;
import com.pbl4.network_server.repository.AlertLogRepository;
import com.pbl4.network_server.repository.AlertThresholdRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AlertService {

    @Autowired
    private AlertThresholdRepository alertThresholdRepository;

    @Autowired
    private AlertLogRepository alertLogRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate; 
    @Transactional(readOnly = true)
    public AlertThreshold getThresholds() {
        return alertThresholdRepository.findById(1L).orElseGet(() -> {
            AlertThreshold defaultThreshold = AlertThreshold.builder()
                    .id(1L)
                    .bwWarnPercent(80.0).bwWarnDuration(5)
                    .latencyWarnMs(80.0).latencyWarnDuration(3)
                    .lossWarnPercent(1.0).lossWarnDuration(2)
                    .bwCritPercent(95.0).bwCritDuration(3)
                    .latencyCritMs(150.0).latencyCritDuration(2)
                    .lossCritPercent(5.0).lossCritDuration(1)
                    .lossCritImmediate(10.0)
                    .updatedAt(LocalDateTime.now())
                    .build();
            return alertThresholdRepository.save(defaultThreshold);
        });
    }

    @Transactional
    public AlertThreshold updateThresholds(AlertThreshold newConfig) {
        AlertThreshold current = getThresholds();

        if (newConfig.getBwWarnPercent() != null) current.setBwWarnPercent(newConfig.getBwWarnPercent());
        if (newConfig.getBwWarnDuration() != null) current.setBwWarnDuration(newConfig.getBwWarnDuration());
        if (newConfig.getLatencyWarnMs() != null) current.setLatencyWarnMs(newConfig.getLatencyWarnMs());
        if (newConfig.getLatencyWarnDuration() != null) current.setLatencyWarnDuration(newConfig.getLatencyWarnDuration());
        if (newConfig.getLossWarnPercent() != null) current.setLossWarnPercent(newConfig.getLossWarnPercent());
        if (newConfig.getLossWarnDuration() != null) current.setLossWarnDuration(newConfig.getLossWarnDuration());

        if (newConfig.getBwCritPercent() != null) current.setBwCritPercent(newConfig.getBwCritPercent());
        if (newConfig.getBwCritDuration() != null) current.setBwCritDuration(newConfig.getBwCritDuration());
        if (newConfig.getLatencyCritMs() != null) current.setLatencyCritMs(newConfig.getLatencyCritMs());
        if (newConfig.getLatencyCritDuration() != null) current.setLatencyCritDuration(newConfig.getLatencyCritDuration());
        if (newConfig.getLossCritPercent() != null) current.setLossCritPercent(newConfig.getLossCritPercent());
        if (newConfig.getLossCritDuration() != null) current.setLossCritDuration(newConfig.getLossCritDuration());
        if (newConfig.getLossCritImmediate() != null) current.setLossCritImmediate(newConfig.getLossCritImmediate());

        current.setUpdatedAt(LocalDateTime.now());
        return alertThresholdRepository.save(current);
    }

    // Ghi nhận cảnh báo mới vào CSDL và phát tin nhắn Toast Popup qua WebSocket
    @Transactional
    public void addAlertLog(String agentId, String hostname, String type, String severity, double value, String unit, double threshold) {
        AlertLog log = AlertLog.builder()
                .agentId(agentId)
                .hostname(hostname)
                .type(type)
                .severity(severity)
                .metricValue(Math.round(value * 10.0) / 10.0)
                .unit(unit)
                .thresholdValue(threshold)
                .createdAt(LocalDateTime.now())
                .build();
        
        AlertLog savedLog = alertLogRepository.saveAndFlush(log);

        // Bắn tin nhắn trực tiếp qua kênh /topic/alerts/toast để Frontend hiện popup
        messagingTemplate.convertAndSend("/topic/alerts/toast", savedLog);
    }

    @Transactional(readOnly = true)
    public List<AlertLog> getAlertLogs() {
        return alertLogRepository.findTop50ByOrderByCreatedAtDesc();
    }
}
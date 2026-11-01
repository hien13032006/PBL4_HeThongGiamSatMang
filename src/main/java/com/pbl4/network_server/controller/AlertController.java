package com.pbl4.network_server.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
@CrossOrigin(origins = "*")
public class AlertController {

    private static final Map<String, Object> thresholdConfig = new HashMap<>();

    static {
        thresholdConfig.put("rxMax", 80.0);
        thresholdConfig.put("latencyMax", 100.0);
        thresholdConfig.put("packetLossMax", 5.0);
    }

    @GetMapping("/thresholds")
    public ResponseEntity<?> getThresholds() {
        return ResponseEntity.ok(thresholdConfig);
    }

    @PostMapping("/thresholds")
    public ResponseEntity<?> updateThresholds(@RequestBody Map<String, Object> newConfig) {
        thresholdConfig.putAll(newConfig);
        return ResponseEntity.ok(Map.of("message", "Success"));
    }

    @GetMapping("/logs")
    public ResponseEntity<?> getAlertLogs() {
        // Khai báo biến logs chuẩn cú pháp Java 21
        List<Map<String, Object>> logs = new ArrayList<>();

        // Trả về danh sách rỗng khi chưa có cảnh báo
        return ResponseEntity.ok(logs);
    }
}
package com.pbl4.network_server.controller;

import com.pbl4.network_server.entity.AlertThreshold;
import com.pbl4.network_server.service.AlertService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/alerts")
@CrossOrigin(origins = "*")
public class AlertController {

    @Autowired
    private AlertService alertService;

    // 1. Lấy toàn bộ thông số ngưỡng cấu hình từ CSDL 
    @GetMapping("/thresholds")
    public ResponseEntity<AlertThreshold> getThresholds() {
        return ResponseEntity.ok(alertService.getThresholds());
    }

    // 2. Lưu/Cập nhật cấu hình ngưỡng mới vào CSDL 
    @PostMapping("/thresholds")
    public ResponseEntity<AlertThreshold> updateThresholds(@RequestBody AlertThreshold newConfig) {
        AlertThreshold updated = alertService.updateThresholds(newConfig);
        return ResponseEntity.ok(updated);
    }

    // 3. Lấy danh sách nhật ký cảnh báo vĩnh viễn từ CSDL
    @GetMapping("/logs")
    public ResponseEntity<?> getAlertLogs() {
        return ResponseEntity.ok(alertService.getAlertLogs());
    }
}
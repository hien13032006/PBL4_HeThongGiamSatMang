package com.pbl4.network_server.socket;

import com.pbl4.network_server.dto.MetricsMessageDto;
import com.pbl4.network_server.service.MetricsService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// Tạo một Component làm nhiệm vụ lấy dữ liệu từ BlockingQueue ra để lưu vào MySQL và đẩy xuống WebSocket cho Dashboard

@Component
public class DataConsumerWorker {

    @Autowired
    private DataQueueManager queueManager;

    @Autowired
    private MetricsService metricsService;

    @PostConstruct
    public void startConsuming() {
        // Chạy luồng ngầm liên tục lấy dữ liệu từ hàng đợi ra xử lý (Consumer)
        new Thread(() -> {
            while (true) {
                try {
                    MetricsMessageDto data = queueManager.takeData();
                    // Xử lý lưu Database và kiểm tra ngưỡng cảnh báo tại đây
                    metricsService.processAndSaveMetrics(data);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
    }
}
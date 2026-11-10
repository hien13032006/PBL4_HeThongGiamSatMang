package com.pbl4.network_server.socket;

import com.pbl4.network_server.dto.MetricsMessageDto;
import com.pbl4.network_server.service.MetricsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Tạo một Component làm nhiệm vụ lấy dữ liệu từ BlockingQueue ra để lưu vào MySQL và đẩy xuống WebSocket cho Dashboard

@Component
public class DataConsumerWorker {

    @Autowired
    private DataQueueManager queueManager;

    @Autowired
    private MetricsService metricsService;

    // Số lượng Worker Threads xử lý song song
    private static final int WORKER_COUNT = 4;
    private final ExecutorService workerPool = Executors.newFixedThreadPool(WORKER_COUNT);
    private volatile boolean isRunning = true;

    @PostConstruct
    public void startConsuming() {
        // Khởi tạo các Worker Threads xử lý dữ liệu đồng thời
        for (int i = 0; i < WORKER_COUNT; i++) {
            workerPool.submit(this::consumeTask);
        }
        System.out.println("[DataConsumerWorker] Đã khởi tạo " + WORKER_COUNT + " luồng Consumer xử lý hàng đợi.");
    }

    private void consumeTask() {
        while (isRunning && !Thread.currentThread().isInterrupted()) {
            try {
                // Lấy dữ liệu từ BlockingQueue (Chờ nếu Queue trống)
                MetricsMessageDto data = queueManager.takeData();
                
                if (data != null) {
                    // Xử lý lưu Database, cập nhật LastSeen và bắn WebSocket
                    metricsService.processAndSaveMetrics(data);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Bắt ngoại lệ để tránh làm chết luồng Worker
                System.err.println("[LỖI CONSUMER] Lỗi xử lý dữ liệu metrics: " + e.getMessage());
            }
        }
    }

    @PreDestroy
    public void stopConsuming() {
        isRunning = false;
        workerPool.shutdownNow();
        System.out.println("[DataConsumerWorker] Đã dừng các luồng Consumer an toàn.");
    }
}
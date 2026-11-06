package com.pbl4.network_server.socket;

import com.pbl4.network_server.dto.MetricsMessageDto;
import com.pbl4.network_server.service.MetricsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class DataConsumerWorker {

    @Autowired
    private DataQueueManager queueManager;

    @Autowired
    private MetricsService metricsService;

    // Tạo ThreadPool gồm 10 - 20 worker để xử lý song song các gói tin từ Queue
    private final ExecutorService workerPool = Executors.newFixedThreadPool(15);
    private volatile boolean isRunning = true;

    @PostConstruct
    public void startConsuming() {
        // Mở 1 luồng chính chuyên lấy dữ liệu ra khỏi Queue và phân phối cho Worker Pool
        new Thread(() -> {
            System.out.println("[CONSUMER WORKER] Đã khởi chạy 15 Worker Threads tiêu thụ Queue song song...");
            while (isRunning) {
                try {
                    // Rút gói tin từ Queue (Blocking nếu queue trống)
                    MetricsMessageDto data = queueManager.takeData();
                    
                    if (data != null) {
                        // ĐẨY NGAY VÀO WORKER POOL ĐỂ XỬ LÝ SONG SONG (Không chờ từng gói)
                        workerPool.submit(() -> {
                            try {
                                metricsService.processAndSaveMetrics(data);
                            } catch (Exception e) {
                                System.err.println("[CONSUMER ERROR] Lỗi xử lý metric của Agent " 
                                        + data.getAgentId() + ": " + e.getMessage());
                            }
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[CONSUMER ERROR] Lỗi rút dữ liệu từ Queue: " + e.getMessage());
                }
            }
        }, "queue-dispatcher-thread").start();
    }

    @PreDestroy
    public void stopWorker() {
        isRunning = false;
        workerPool.shutdownNow();
    }
}
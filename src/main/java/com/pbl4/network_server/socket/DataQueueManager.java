package com.pbl4.network_server.socket;

import com.pbl4.network_server.dto.MetricsMessageDto; // Class DTO nhận JSON từ Agent
import org.springframework.stereotype.Component;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

// Lớp quản lý hàng đợi dữ liệu Metrics từ Agent, dùng cho Producer-Consumer Pattern

@Component
public class DataQueueManager {
    // Sức chứa hàng đợi tối đa 10000 bản ghi
    private final BlockingQueue<MetricsMessageDto> queue = new ArrayBlockingQueue<>(10000);

    public void putData(MetricsMessageDto data) {
        try {
            queue.put(data); // Producer đưa dữ liệu vào
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public MetricsMessageDto takeData() throws InterruptedException {
        return queue.take(); // Consumer lấy dữ liệu ra để xử lý
    }
}
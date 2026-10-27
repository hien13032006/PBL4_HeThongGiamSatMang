package com.pbl4.network_server.socket;

import com.pbl4.network_server.dto.MetricsMessageDto;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

// Lớp quản lý hàng đợi dữ liệu Metrics từ Agent, dùng cho Producer-Consumer Pattern

@Component
public class DataQueueManager {

    // Sức chứa hàng đợi tối đa 10.000 bản ghi trong RAM
    private final BlockingQueue<MetricsMessageDto> queue = new ArrayBlockingQueue<>(10000);
    public boolean putData(MetricsMessageDto data) {
        try {
            // Chờ tối đa 100ms nếu Queue đầy, không treo luồng Socket vô thời hạn
            boolean success = queue.offer(data, 100, TimeUnit.MILLISECONDS);
            if (!success) {
                System.err.println("[CẢNH BÁO QUEUE] Hàng đợi dữ liệu bị đầy (10.000 bản ghi)! Đã bỏ qua 1 gói tin từ Agent: " 
                        + (data != null ? data.getAgentId() : "N/A"));
            }
            return success;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    //Consumer (DataConsumerWorker) gọi hàm này để lấy dữ liệu ra xử lý
    public MetricsMessageDto takeData() throws InterruptedException {
        return queue.take(); // Lấy phần tử ra, chờ nếu Queue đang trống
    }

    //Lấy số lượng bản ghi hiện đang nằm trong hàng đợi
    public int getQueueSize() {
        return queue.size();
    }
}
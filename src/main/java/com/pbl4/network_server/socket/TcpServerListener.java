package com.pbl4.network_server.socket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbl4.network_server.dto.MetricsMessageDto;
import com.pbl4.network_server.service.AgentService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class TcpServerListener {

    private static final int PORT = 9999;
    private static final int BACKLOG = 500;
    
    // Đổi sang FixedThreadPool (tối đa 100 luồng) để tránh bùng nổ Thread gây quá tải CPU máy Host
    private final ExecutorService threadPool = Executors.newFixedThreadPool(100);
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DataQueueManager queueManager;

    @Autowired
    private AgentService agentService; 

    private ServerSocket serverSocket;
    private volatile boolean isRunning = true;

    @PostConstruct
    public void startServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT, BACKLOG);
                System.out.println("TCP Server đang lắng nghe trên cổng " + PORT + " (Backlog: " + BACKLOG + ")...");

                while (isRunning) {
                    Socket clientSocket = serverSocket.accept();
                    
                    // Tối ưu cấu hình Socket cho truyền tải thời gian thực
                    clientSocket.setTcpNoDelay(true); 
                    // Tăng SoTimeout lên 60 giây (hoặc tắt bằng 0) để tránh nghẽn DB do disconnect liên tục
                    clientSocket.setSoTimeout(60000); 

                    threadPool.submit(() -> handleClient(clientSocket));
                }
            } catch (Exception e) {
                if (isRunning) {
                    System.err.println("Lỗi ServerSocket: " + e.getMessage());
                }
            }
        }, "tcp-listener-main").start();
    }

    private void handleClient(Socket socket) {
        String currentAgentId = null;
        String clientIp = socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "Unknown";

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                MetricsMessageDto message = objectMapper.readValue(line, MetricsMessageDto.class);
                
                if (message != null) {
                    if (message.getAgentId() != null) {
                        currentAgentId = message.getAgentId();
                    }
                    
                    if (message.getIpAddress() == null || message.getIpAddress().isEmpty()) {
                        message.setIpAddress(clientIp);
                    }

                    // Đẩy dữ liệu vào Queue nhanh chóng mà không làm nghẽn luồng đọc Socket
                    queueManager.putData(message);
                }
            }
        } catch (Exception e) {
            // Log ngắt kết nối nhẹ nhàng
            System.out.println("Agent " + (currentAgentId != null ? currentAgentId : clientIp) + " ngắt kết nối TCP.");
        } finally {
            if (currentAgentId != null) {
                try {
                    agentService.markAgentOffline(currentAgentId);
                } catch (Exception ex) {
                    System.err.println("Lỗi khi chuyển trạng thái Offline cho Agent " + currentAgentId + ": " + ex.getMessage());
                }
            }
            try {
                if (!socket.isClosed()) socket.close();
            } catch (Exception ignored) {}
        }
    }

    @PreDestroy
    public void stopServer() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            threadPool.shutdownNow();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
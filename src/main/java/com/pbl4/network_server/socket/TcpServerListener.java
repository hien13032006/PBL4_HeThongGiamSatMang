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

// Tạo một luồng chạy ngầm khi Spring Boot khởi động để mở ServerSocket, 
// lắng nghe kết nối TCP từ Agent và đưa dữ liệu vào hàng đợi để xử lý

@Component
public class TcpServerListener {

    private static final int PORT = 9999;
    private static final int BACKLOG = 500; //hàng đợi chờ kết nối TCP 
    
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    
    // Tái sử dụng ObjectMapper (Thread-safe) tránh new liên tục trong while
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
                // Khởi tạo ServerSocket kèm tham số Backlog
                serverSocket = new ServerSocket(PORT, BACKLOG);
                System.out.println("TCP Server đang lắng nghe trên cổng " + PORT + " (Backlog: " + BACKLOG + ")...");

                while (isRunning) {
                    Socket clientSocket = serverSocket.accept();
                    
                    // Tối ưu Socket Buffer cho từng Client
                    clientSocket.setTcpNoDelay(true); // Gửi gói tin ngay không chờ Nagle Algorithm
                    clientSocket.setSoTimeout(15000); // 15s Không có dữ liệu sẽ Timeout

                    // Giao cho Thread Pool xử lý
                    threadPool.submit(() -> handleClient(clientSocket));
                }
            } catch (Exception e) {
                if (isRunning) {
                    System.err.println("Lỗi ServerSocket: " + e.getMessage());
                }
            }
        }).start();
    }

    private void handleClient(Socket socket) {
        String currentAgentId = null;
        String clientIp = socket.getInetAddress().getHostAddress();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                // Parse JSON thành DTO
                MetricsMessageDto message = objectMapper.readValue(line, MetricsMessageDto.class);
                
                if (message != null) {
                    currentAgentId = message.getAgentId();
                    
                    // Gán IP kết nối nếu trong DTO bị thiếu
                    if (message.getIpAddress() == null || message.getIpAddress().isEmpty()) {
                        message.setIpAddress(clientIp);
                    }

                    // Đẩy vào Queue xử lý bất đồng bộ (Producer)
                    queueManager.putData(message);
                }
            }
        } catch (Exception e) {
            // Xử lý khi Agent ngắt kết nối hoặc mất mạng (Socket Timeout / Connection Reset)
            System.out.println("Agent " + (currentAgentId != null ? currentAgentId : clientIp) + " đã ngắt kết nối.");
        } finally {
            // Cập nhật trạng thái OFFLINE vào CSDL và bắn WebSocket nếu Agent thoát
            if (currentAgentId != null) {
                agentService.markAgentOffline(currentAgentId);
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
            threadPool.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
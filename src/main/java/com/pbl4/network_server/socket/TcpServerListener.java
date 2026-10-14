package com.pbl4.network_server.socket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbl4.network_server.dto.MetricsMessageDto;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Tạo một luồng chạy ngầm khi Spring Boot khởi động để mở ServerSocket, lắng nghe kết nối TCP từ Agent và đưa dữ liệu vào hàng đợi để xử lý

@Component
public class TcpServerListener {

    private static final int PORT = 9999; // Cổng TCP Server nhận dữ liệu từ Agent
    private final ExecutorService threadPool = Executors.newFixedThreadPool(20); // Thread Pool quản lý đồng thời Agent
    
    @Autowired
    private DataQueueManager queueManager;

    @PostConstruct
    public void startServer() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                System.out.println("TCP Server đang lắng nghe trên cổng " + PORT + "...");
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    // Giao mỗi kết nối Agent cho một Thread trong Pool xử lý
                    threadPool.submit(() -> handleClient(clientSocket));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void handleClient(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            ObjectMapper objectMapper = new ObjectMapper();
            while ((line = reader.readLine()) != null) { // Đọc theo dòng (Delimiter-based framing)
                // Chuyển chuỗi JSON từ Agent thành DTO Object
                MetricsMessageDto message = objectMapper.readValue(line, MetricsMessageDto.class);
                // Đẩy vào hàng đợi (Producer)
                queueManager.putData(message);
            }
        } catch (Exception e) {
            System.out.println("Agent da ngat ket noi: " + socket.getInetAddress());
        }
    }
}
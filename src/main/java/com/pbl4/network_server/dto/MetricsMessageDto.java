package com.pbl4.network_server.dto;

import com.pbl4.network_server.entity.Agent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Class này đóng vai trò làm khung chứa để giải mã chuỗi JSON mà 
// Agent gửi lên qua TCP Socket (bao gồm gói tin Register đăng ký lẫn gói tin Metrics định kì)

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetricsMessageDto {

    // Phân loại thông điệp: "REGISTER", "METRICS", hoặc "PING"
    private String type;

    // Định danh Agent
    private String agentId;

    // Thông tin metadata của Agent (chỉ dùng khi type = "REGISTER")
    private String hostname;
    private String ipAddress;
    private String macAddress;
    private String os;
    private String nicName;
    private Double maxSpeed;
    
    // Mốc thời gian gửi dữ liệu
    private Long timestamp;

    // Đối tượng Agent chứa thông tin đầy đủ lưu trong DB (đã cập nhật trạng thái/metadata)
    // Để gán và đẩy kèm xuống WebSocket cho Frontend
    private Agent agent;

    // Đối tượng chứa các chỉ số mạng chi tiết (dùng khi type = "METRICS")
    private MetricsDetailDto metrics;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricsDetailDto {
        private Double rxRate;      // Tốc độ nhận (KB/s -> đổi sang MB/s ở Service)
        private Double txRate;      // Tốc độ gửi (KB/s -> đổi sang MB/s ở Service)
        private Long totalRx;       // Tổng dung lượng nhận (Bytes)
        private Long totalTx;       // Tổng dung lượng gửi (Bytes)
        private Integer rxPacketRate; // Số gói tin nhận mỗi giây (packets/s)
        private Integer txPacketRate; // Số gói tin gửi mỗi giây (packets/s)
        private Double latency;     // Độ trễ (ms)
        private Double packetLoss;  // Tỷ lệ mất gói (%)
        private Double maxSpeed;    // Tốc độ tối đa card mạng
        private Double networkUsagePercent; // Phần trăm tải card mạng (%)
    }
}
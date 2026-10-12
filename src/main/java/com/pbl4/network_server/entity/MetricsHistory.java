package com.pbl4.network_server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "metrics_history", indexes = {
    @Index(name = "idx_agent_time", columnList = "agent_id, timestamp DESC")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetricsHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "rx_rate")
    private Double rxRate; // KB/s

    @Column(name = "tx_rate")
    private Double txRate; // KB/s

    @Column(name = "rx_packet_rate")
    private Integer rxPacketRate;

    @Column(name = "tx_packet_rate")
    private Integer txPacketRate;

    private Double latency; // ms

    @Column(name = "packet_loss")
    private Double packetLoss; // %
}

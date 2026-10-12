package com.pbl4.network_server.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agents")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Agent {

    @Id
    @Column(name = "agent_id", length = 50)
    private String agentId;

    @Column(nullable = false, length = 100)
    private String hostname;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "mac_address", nullable = false, length = 17)
    private String macAddress;

    @Column(length = 50)
    private String os;

    @Column(name = "nic_name", length = 100)
    private String nicName;

    @Column(length = 20)
    private String status; // "ONLINE" hoặc "OFFLINE"

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.lastSeen = LocalDateTime.now();
    }
}
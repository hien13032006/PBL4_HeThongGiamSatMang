package com.pbl4.network_server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "alert_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String agentId;
    private String hostname;
    private String type;         
    private String severity;     
    private Double metricValue;  
    private String unit;         
    private Double thresholdValue;

    private LocalDateTime createdAt;
}
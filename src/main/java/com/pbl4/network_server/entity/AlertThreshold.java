package com.pbl4.network_server.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "alert_thresholds")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertThreshold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Warning
    private Double bwWarnPercent;
    private Integer bwWarnDuration;
    private Double latencyWarnMs;
    private Integer latencyWarnDuration;
    private Double lossWarnPercent;
    private Integer lossWarnDuration;

    // Critical
    private Double bwCritPercent;
    private Integer bwCritDuration;
    private Double latencyCritMs;
    private Integer latencyCritDuration;
    private Double lossCritPercent;
    private Integer lossCritDuration;
    private Double lossCritImmediate;

    private LocalDateTime updatedAt;
}
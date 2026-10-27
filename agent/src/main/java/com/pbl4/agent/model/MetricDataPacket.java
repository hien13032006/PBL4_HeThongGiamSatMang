package com.pbl4.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MetricDataPacket {
    private String type;
    private String agentId;
    private long timestamp;
    private Metrics metrics;

    public MetricDataPacket() {
    }

    public MetricDataPacket(String agentId, long timestamp, Metrics metrics) {
        this.type = "METRICS";
        this.agentId = agentId;
        this.timestamp = timestamp;
        this.metrics = metrics;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public Metrics getMetrics() { return metrics; }
    public void setMetrics(Metrics metrics) { this.metrics = metrics; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Metrics(
            Double rxRate,
            Double txRate,
            Long totalRx,
            Long totalTx,
            Integer rxPacketRate,
            Integer txPacketRate,
            Double latency,
            Double packetLoss) {
    }
}

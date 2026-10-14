package com.pbl4.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MetricDataPacket {
    private String agentId;
    private Instant timestamp;
    private double rxRate;
    private double txRate;
    private double rxPacketRate;
    private Double latency;
    private Double packetLoss;
    private long rxBytesTotal;
    private long txBytesTotal;
    private long totalPackets;

    public MetricDataPacket() {
    }

    public MetricDataPacket(String agentId, Instant timestamp, double rxRate, double txRate,
                           double rxPacketRate, Double latency, Double packetLoss,
                           long rxBytesTotal, long txBytesTotal, long totalPackets) {
        this.agentId = agentId;
        this.timestamp = timestamp;
        this.rxRate = rxRate;
        this.txRate = txRate;
        this.rxPacketRate = rxPacketRate;
        this.latency = latency;
        this.packetLoss = packetLoss;
        this.rxBytesTotal = rxBytesTotal;
        this.txBytesTotal = txBytesTotal;
        this.totalPackets = totalPackets;
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public double getRxRate() { return rxRate; }
    public void setRxRate(double rxRate) { this.rxRate = rxRate; }

    public double getTxRate() { return txRate; }
    public void setTxRate(double txRate) { this.txRate = txRate; }

    public double getRxPacketRate() { return rxPacketRate; }
    public void setRxPacketRate(double rxPacketRate) { this.rxPacketRate = rxPacketRate; }

    public Double getLatency() { return latency; }
    public void setLatency(Double latency) { this.latency = latency; }

    public Double getPacketLoss() { return packetLoss; }
    public void setPacketLoss(Double packetLoss) { this.packetLoss = packetLoss; }

    public long getRxBytesTotal() { return rxBytesTotal; }
    public void setRxBytesTotal(long rxBytesTotal) { this.rxBytesTotal = rxBytesTotal; }

    public long getTxBytesTotal() { return txBytesTotal; }
    public void setTxBytesTotal(long txBytesTotal) { this.txBytesTotal = txBytesTotal; }

    public long getTotalPackets() { return totalPackets; }
    public void setTotalPackets(long totalPackets) { this.totalPackets = totalPackets; }
}

package com.pbl4.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HeartbeatPacket {
    private String agentId;
    private String type;
    private Instant timestamp;
    private boolean connected;

    public HeartbeatPacket() {
    }

    public HeartbeatPacket(String agentId, String type, Instant timestamp, boolean connected) {
        this.agentId = agentId;
        this.type = type;
        this.timestamp = timestamp;
        this.connected = connected;
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
}

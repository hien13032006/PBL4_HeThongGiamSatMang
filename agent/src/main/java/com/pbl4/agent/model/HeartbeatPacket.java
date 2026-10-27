package com.pbl4.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HeartbeatPacket {
    private String agentId;
    private String type;
    private long timestamp;

    public HeartbeatPacket() {
    }

    public HeartbeatPacket(String agentId, String type, long timestamp) {
        this.agentId = agentId;
        this.type = type;
        this.timestamp = timestamp;
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}

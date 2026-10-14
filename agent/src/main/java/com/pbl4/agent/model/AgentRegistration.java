package com.pbl4.agent.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentRegistration {
    private String agentId;
    private String hostname;
    private String ipAddress;
    private String macAddress;
    private String os;
    private String nicName;
    private String status;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant createdAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant lastSeen;

    public AgentRegistration() {
    }

    public AgentRegistration(String agentId, String hostname, String ipAddress, String macAddress,
                            String os, String nicName, String status, Instant createdAt, Instant lastSeen) {
        this.agentId = agentId;
        this.hostname = hostname;
        this.ipAddress = ipAddress;
        this.macAddress = macAddress;
        this.os = os;
        this.nicName = nicName;
        this.status = status;
        this.createdAt = createdAt;
        this.lastSeen = lastSeen;
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getMacAddress() { return macAddress; }
    public void setMacAddress(String macAddress) { this.macAddress = macAddress; }

    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }

    public String getNicName() { return nicName; }
    public void setNicName(String nicName) { this.nicName = nicName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }
}

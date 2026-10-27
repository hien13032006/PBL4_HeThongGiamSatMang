package com.pbl4.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentRegistration {
    private String type;
    private String agentId;
    private String hostname;
    private String ipAddress;
    private String macAddress;
    private String os;
    private String nicName;
    private long timestamp;

    public AgentRegistration() {
    }

    public AgentRegistration(String agentId, String hostname, String ipAddress, String macAddress,
                            String os, String nicName, long timestamp) {
        this.type = "REGISTER";
        this.agentId = agentId;
        this.hostname = hostname;
        this.ipAddress = ipAddress;
        this.macAddress = macAddress;
        this.os = os;
        this.nicName = nicName;
        this.timestamp = timestamp;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

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

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}

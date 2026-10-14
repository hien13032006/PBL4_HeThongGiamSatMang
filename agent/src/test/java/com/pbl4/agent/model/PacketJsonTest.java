package com.pbl4.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PacketJsonTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void registrationSerializesAgreedAgentNames() throws Exception {
        AgentRegistration registration = new AgentRegistration(
                "agent-1", "host", "192.0.2.1", "00:11:22:33:44:55",
                "Windows 10", "Ethernet", "ONLINE",
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(registration));

        for (String field : new String[]{"agentId", "hostname", "ipAddress", "macAddress",
                "os", "nicName", "status", "createdAt", "lastSeen"}) {
            assertTrue(json.has(field), "missing JSON field " + field);
        }
        assertFalse(json.has("osName"));
        assertFalse(json.has("networkInterface"));
        assertFalse(json.has("registeredAt"));
    }

    @Test
    void metricSerializesAgreedNamesAndOmitsUnmeasuredPacketLoss() throws Exception {
        MetricDataPacket packet = new MetricDataPacket(
                "agent-1", Instant.parse("2026-10-02T00:00:00Z"),
                2.5, 1.5, 3.0, null, null, 100L, 50L, 4L);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(packet));

        assertEquals(2.5, json.get("rxRate").asDouble());
        assertEquals(1.5, json.get("txRate").asDouble());
        assertEquals(3.0, json.get("rxPacketRate").asDouble());
        assertFalse(json.has("rxRateKbps"));
        assertFalse(json.has("packetRatePerSecond"));
        assertFalse(json.has("packetLoss"));
    }
}

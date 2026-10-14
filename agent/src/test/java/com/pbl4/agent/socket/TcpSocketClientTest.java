package com.pbl4.agent.socket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbl4.agent.capture.PacketCaptureService;
import com.pbl4.agent.metrics.NetworkMetricsService;
import com.pbl4.agent.model.AgentRegistration;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpSocketClientTest {
    @Test
    void sendsRegistrationAndMetricAsSeparateJsonLines() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(5000);
            PacketCaptureService capture = new PacketCaptureService();
            NetworkMetricsService metrics = new NetworkMetricsService(capture);
            AgentRegistration registration = new AgentRegistration(
                    "stable-agent", "host", "192.0.2.1", null, "Windows", "Ethernet",
                    "ONLINE", Instant.now(), Instant.now());
            TcpSocketClient client = new TcpSocketClient(
                    "127.0.0.1", server.getLocalPort(), registration, 100, 100, metrics);

            client.start();
            try (Socket connection = server.accept()) {
                connection.setSoTimeout(5000);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8))) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode registrationJson = mapper.readTree(reader.readLine());
                    JsonNode metricJson = mapper.readTree(reader.readLine());

                    assertEquals("stable-agent", registrationJson.get("agentId").asText());
                    assertTrue(registrationJson.has("createdAt"));
                    assertEquals("stable-agent", metricJson.get("agentId").asText());
                    assertTrue(metricJson.has("rxRate"));
                    assertTrue(metricJson.has("txRate"));
                    assertTrue(metricJson.has("rxPacketRate"));
                }
            } finally {
                client.close();
                capture.close();
            }
        }
    }

    @Test
    void reconnectRegistersSameAgentAgain() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(5000);
            PacketCaptureService capture = new PacketCaptureService();
            NetworkMetricsService metrics = new NetworkMetricsService(capture);
            AgentRegistration registration = new AgentRegistration(
                    "stable-agent", "host", "192.0.2.1", null, "Windows", "Ethernet",
                    "ONLINE", Instant.now(), Instant.now());
            TcpSocketClient client = new TcpSocketClient(
                    "127.0.0.1", server.getLocalPort(), registration, 100, 50, metrics);

            client.start();
            try {
                Socket firstConnection = server.accept();
                firstConnection.setSoTimeout(5000);
                try (firstConnection;
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             firstConnection.getInputStream(), StandardCharsets.UTF_8))) {
                    reader.readLine();
                    reader.readLine();
                }

                try (Socket secondConnection = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             secondConnection.getInputStream(), StandardCharsets.UTF_8))) {
                    secondConnection.setSoTimeout(5000);
                    JsonNode registrationJson = new ObjectMapper().readTree(reader.readLine());
                    assertEquals("stable-agent", registrationJson.get("agentId").asText());
                }
            } finally {
                client.close();
                capture.close();
            }
        }
    }
}

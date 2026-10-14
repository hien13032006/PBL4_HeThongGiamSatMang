package com.pbl4.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pbl4.agent.capture.PacketCaptureService;
import com.pbl4.agent.config.AgentConfig;
import com.pbl4.agent.metrics.NetworkMetricsService;
import com.pbl4.agent.model.AgentRegistration;
import com.pbl4.agent.socket.TcpSocketClient;
import com.pbl4.agent.util.AgentIdentity;
import com.pbl4.agent.util.HostInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;

public class AgentApp {
    private static final Logger log = LoggerFactory.getLogger(AgentApp.class);

    public static void main(String[] args) {
        AgentConfig config = new AgentConfig();

        AgentIdentity identity;
        try {
            identity = AgentIdentity.loadOrCreate();
        } catch (Exception e) {
            log.error("Unable to load or persist the agent identity", e);
            System.exit(1);
            return;
        }

        String agentId = identity.agentId();
        String hostname = HostInfo.getHostname();
        HostInfo.NetworkDetails networkDetails = HostInfo.getPrimaryNetworkDetails();
        String os = HostInfo.getOsName();
        Instant now = Instant.now();

        AgentRegistration registration = new AgentRegistration(
                agentId,
                hostname,
                networkDetails.ipAddress(),
                networkDetails.macAddress(),
                os,
                networkDetails.nicName(),
                "ONLINE",
                Instant.parse(identity.createdAt()),
                now
        );

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        try {
            String json = objectMapper.writeValueAsString(registration);
            log.info("Agent config loaded. Server host={}, port={}", config.getServerHost(), config.getServerPort());
            log.info("Agent registration payload: {}", json);
        } catch (Exception e) {
            log.error("Failed to serialize agent registration payload", e);
            System.exit(1);
        }

        PacketCaptureService packetCaptureService = new PacketCaptureService();
        NetworkMetricsService metricsService = new NetworkMetricsService(packetCaptureService);

        try (TcpSocketClient client = new TcpSocketClient(
                config.getServerHost(),
                config.getServerPort(),
                registration,
                config.getReconnectDelayMs(),
                config.getCollectIntervalMs(),
                metricsService)) {
            packetCaptureService.start();
            client.start();
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Agent runtime interrupted", e);
        } finally {
            packetCaptureService.stop();
        }
    }
}

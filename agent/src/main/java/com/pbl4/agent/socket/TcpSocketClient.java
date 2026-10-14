package com.pbl4.agent.socket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pbl4.agent.metrics.NetworkMetricsService;
import com.pbl4.agent.model.AgentRegistration;
import com.pbl4.agent.model.HeartbeatPacket;
import com.pbl4.agent.model.MetricDataPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TcpSocketClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TcpSocketClient.class);

    private final String host;
    private final int port;
    private final String agentId;
    private final int reconnectDelayMs;
    private final int collectIntervalMs;
    private final AgentRegistration registration;
    private final ObjectMapper objectMapper;
    private final NetworkMetricsService metricsService;
    private final BlockingQueue<String> outboundQueue = new ArrayBlockingQueue<>(100);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(2);

    public TcpSocketClient(String host, int port, AgentRegistration registration, int reconnectDelayMs,
                           int collectIntervalMs, NetworkMetricsService metricsService) {
        this.host = host;
        this.port = port;
        this.registration = registration;
        this.agentId = registration.getAgentId();
        this.reconnectDelayMs = reconnectDelayMs;
        this.collectIntervalMs = collectIntervalMs;
        this.metricsService = metricsService;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void start() {
        Thread reconnectThread = new Thread(this::connectionLoop, "tcp-connection-loop");
        reconnectThread.setDaemon(true);
        reconnectThread.start();

        scheduler.scheduleAtFixedRate(this::enqueueMetric, collectIntervalMs, collectIntervalMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::enqueueHeartbeat, 5, 5, TimeUnit.SECONDS);
    }

    private void connectionLoop() {
        while (running.get()) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 3000);
                try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    log.info("Connected to TCP server {}:{}", host, port);
                    registration.setLastSeen(Instant.now());
                    sendMessage(writer, writeAsJson(registration));

                    while (running.get() && !socket.isClosed()) {
                        String message = outboundQueue.poll(500, TimeUnit.MILLISECONDS);
                        if (message != null) {
                            sendMessage(writer, message);
                        }
                        if (reader.ready()) {
                            String inbound = reader.readLine();
                            if (inbound == null) {
                                throw new IOException("TCP server closed the connection");
                            }
                            if (!inbound.isBlank()) {
                                log.info("Received server response: {}", inbound);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("TCP connection failed for {}:{}, retrying in {} ms", host, port, reconnectDelayMs, e);
            }

            try {
                Thread.sleep(reconnectDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void enqueueMetric() {
        if (!running.get()) {
            return;
        }
        try {
            NetworkMetricsService.Snapshot snapshot = metricsService.computeSnapshot();

            MetricDataPacket packet = new MetricDataPacket(
                    agentId,
                    Instant.now(),
                    snapshot.rxRate(),
                    snapshot.txRate(),
                    snapshot.rxPacketRate(),
                    null,
                    null,
                    metricsService.getPacketCaptureService().getRxBytes(),
                    metricsService.getPacketCaptureService().getTxBytes(),
                    metricsService.getPacketCaptureService().getTotalPackets()
            );
            enqueue(writeAsJson(packet));
        } catch (Exception e) {
            log.error("Failed to prepare metric packet", e);
        }
    }

    private void enqueueHeartbeat() {
        if (!running.get()) {
            return;
        }
        try {
            registration.setLastSeen(Instant.now());
            enqueue(writeAsJson(new HeartbeatPacket(agentId, "heartbeat", registration.getLastSeen(), true)));
        } catch (Exception e) {
            log.error("Failed to prepare heartbeat packet", e);
        }
    }

    private void enqueue(String message) {
        if (!outboundQueue.offer(message)) {
            log.warn("Outbound queue is full; dropping the newest message for agent {}", agentId);
        }
    }

    private String writeAsJson(Object payload) throws IOException {
        return objectMapper.writeValueAsString(payload) + "\n";
    }

    private void sendMessage(PrintWriter writer, String message) throws IOException {
        writer.println(message.trim());
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("Failed to write message to TCP server");
        }
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdownNow();
    }
}

package com.pbl4.agent.metrics;

import com.pbl4.agent.capture.PacketCaptureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

public class NetworkMetricsService {
    private static final Logger log = LoggerFactory.getLogger(NetworkMetricsService.class);
    private static final int PROBE_WINDOW_SIZE = 20;

    private final PacketCaptureService packetCaptureService;
    private final AtomicLong lastRxBytes = new AtomicLong();
    private final AtomicLong lastTxBytes = new AtomicLong();
    private final AtomicLong lastRxPackets = new AtomicLong();
    private final AtomicLong lastTxPackets = new AtomicLong();
    private final ArrayDeque<ProbeSample> probeWindow = new ArrayDeque<>();
    private long lastSnapshotNanos = System.nanoTime();

    public NetworkMetricsService(PacketCaptureService packetCaptureService) {
        this.packetCaptureService = packetCaptureService;
    }

    public PacketCaptureService getPacketCaptureService() {
        return packetCaptureService;
    }

    public synchronized Snapshot computeSnapshot() {
        long now = System.nanoTime();
        long rxBytes = packetCaptureService.getRxBytes();
        long txBytes = packetCaptureService.getTxBytes();
        long rxPackets = packetCaptureService.getRxPackets();
        long txPackets = packetCaptureService.getTxPackets();

        long elapsedNanos = Math.max(1, now - lastSnapshotNanos);
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double rxRate = calculateRateKbps(rxBytes - lastRxBytes.get(), elapsedSeconds);
        double txRate = calculateRateKbps(txBytes - lastTxBytes.get(), elapsedSeconds);
        double rxPacketRate = calculatePacketRate(rxPackets - lastRxPackets.get(), elapsedSeconds);
        double txPacketRate = calculatePacketRate(txPackets - lastTxPackets.get(), elapsedSeconds);

        lastRxBytes.set(rxBytes);
        lastTxBytes.set(txBytes);
        lastRxPackets.set(rxPackets);
        lastTxPackets.set(txPackets);
        lastSnapshotNanos = now;

        ProbeSnapshot probeSnapshot = getProbeSnapshot();
        return new Snapshot(rxRate, txRate, rxPacketRate, txPacketRate,
                rxBytes, txBytes, probeSnapshot.latency(), probeSnapshot.packetLoss());
    }

    public void probeConnectivity(String target, int timeoutMs) {
        boolean reachable = false;
        double latencyMs = 0;
        try {
            InetAddress address = InetAddress.getByName(target);
            long startNanos = System.nanoTime();
            reachable = address.isReachable(timeoutMs);
            latencyMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        } catch (IOException e) {
            log.warn("Connectivity probe to {} failed", target, e);
        }

        synchronized (probeWindow) {
            probeWindow.addLast(new ProbeSample(reachable, latencyMs));
            if (probeWindow.size() > PROBE_WINDOW_SIZE) {
                probeWindow.removeFirst();
            }
        }
    }

    private ProbeSnapshot getProbeSnapshot() {
        synchronized (probeWindow) {
            if (probeWindow.isEmpty()) {
                return new ProbeSnapshot(null, null);
            }
            long successfulProbes = probeWindow.stream().filter(ProbeSample::reachable).count();
            double packetLoss = (probeWindow.size() - successfulProbes) * 100.0 / probeWindow.size();
            double latency = probeWindow.stream()
                    .filter(ProbeSample::reachable)
                    .mapToDouble(ProbeSample::latencyMs)
                    .average()
                    .orElse(Double.NaN);
            return new ProbeSnapshot(Double.isNaN(latency) ? null : latency, packetLoss);
        }
    }

    static double calculateRateKbps(long deltaBytes, double elapsedSeconds) {
        return Math.max(0.0, (deltaBytes / 1024.0) / elapsedSeconds);
    }

    static double calculatePacketRate(long deltaPackets, double elapsedSeconds) {
        return Math.max(0.0, deltaPackets / elapsedSeconds);
    }

    private record ProbeSample(boolean reachable, double latencyMs) {
    }

    private record ProbeSnapshot(Double latency, Double packetLoss) {
    }

    public record Snapshot(double rxRate, double txRate, double rxPacketRate, double txPacketRate,
                           long totalRx, long totalTx, Double latency, Double packetLoss) {
    }
}

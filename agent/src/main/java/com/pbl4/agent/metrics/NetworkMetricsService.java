package com.pbl4.agent.metrics;

import com.pbl4.agent.capture.PacketCaptureService;

import java.util.concurrent.atomic.AtomicLong;

public class NetworkMetricsService {
    private final PacketCaptureService packetCaptureService;
    private final AtomicLong lastRxBytes = new AtomicLong();
    private final AtomicLong lastTxBytes = new AtomicLong();
    private final AtomicLong lastRxPackets = new AtomicLong();
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

        long elapsedNanos = Math.max(1, now - lastSnapshotNanos);
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double rxRate = calculateRateKbps(rxBytes - lastRxBytes.get(), elapsedSeconds);
        double txRate = calculateRateKbps(txBytes - lastTxBytes.get(), elapsedSeconds);
        double rxPacketRate = calculatePacketRate(rxPackets - lastRxPackets.get(), elapsedSeconds);

        lastRxBytes.set(rxBytes);
        lastTxBytes.set(txBytes);
        lastRxPackets.set(rxPackets);
        lastSnapshotNanos = now;

        return new Snapshot(rxRate, txRate, rxPacketRate);
    }

    static double calculateRateKbps(long deltaBytes, double elapsedSeconds) {
        return Math.max(0.0, (deltaBytes / 1024.0) / elapsedSeconds);
    }

    static double calculatePacketRate(long deltaPackets, double elapsedSeconds) {
        return Math.max(0.0, deltaPackets / elapsedSeconds);
    }

    public record Snapshot(double rxRate, double txRate, double rxPacketRate) {
    }
}

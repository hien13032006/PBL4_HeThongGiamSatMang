package com.pbl4.agent.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetworkMetricsServiceTest {
    @Test
    void ratesUseByteAndPacketDeltasPerElapsedSecond() {
        assertEquals(2.0, NetworkMetricsService.calculateRateKbps(2048, 1.0));
        assertEquals(5.0, NetworkMetricsService.calculatePacketRate(10, 2.0));
        assertEquals(0.0, NetworkMetricsService.calculateRateKbps(-10, 1.0));
        assertEquals(0.0, NetworkMetricsService.calculatePacketRate(-1, 1.0));
    }
}

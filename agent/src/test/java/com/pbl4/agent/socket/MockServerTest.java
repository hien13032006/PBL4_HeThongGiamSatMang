package com.pbl4.agent.socket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MockServerTest {
    @Test
    void formatsMetricMessageAsReadableMultiLineJson() throws Exception {
        String formatted = MockServer.formatMessage(
                3, "/127.0.0.1:54321", "{\"agentId\":\"agent-1\",\"rxRate\":1.25}");

        assertTrue(formatted.contains("Message #3 | METRICS | /127.0.0.1:54321"));
        assertTrue(formatted.contains(System.lineSeparator() + "{"));
        assertTrue(formatted.contains(System.lineSeparator() + "  \"agentId\" : \"agent-1\""));
    }
}

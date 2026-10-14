package com.pbl4.agent.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentIdentityTest {
    @TempDir
    Path tempDirectory;

    @Test
    void identityIsStableAcrossLoads() throws Exception {
        Path identityFile = tempDirectory.resolve("agent").resolve("identity.properties");

        AgentIdentity first = AgentIdentity.loadOrCreate(identityFile);
        AgentIdentity second = AgentIdentity.loadOrCreate(identityFile);

        assertEquals(first, second);
    }
}

package com.pbl4.agent.util;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

public record AgentIdentity(String agentId, String createdAt) {
    private static final String ID_KEY = "agent.id";
    private static final String CREATED_AT_KEY = "agent.created-at";

    public static AgentIdentity loadOrCreate() throws IOException {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new IOException("Cannot persist agent identity because user.home is not set");
        }
        return loadOrCreate(Path.of(userHome, ".pbl4-agent", "identity.properties"));
    }

    static AgentIdentity loadOrCreate(Path identityFile) throws IOException {
        if (Files.exists(identityFile)) {
            return read(identityFile);
        }

        Files.createDirectories(identityFile.getParent());
        AgentIdentity identity = new AgentIdentity(UUID.randomUUID().toString(),
                java.time.Instant.now().toString());
        Properties properties = new Properties();
        properties.setProperty(ID_KEY, identity.agentId());
        properties.setProperty(CREATED_AT_KEY, identity.createdAt());
        try (var output = Files.newOutputStream(identityFile, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            properties.store(output, "PBL4 network agent identity");
            return identity;
        } catch (FileAlreadyExistsException e) {
            return read(identityFile);
        }
    }

    private static AgentIdentity read(Path identityFile) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(identityFile)) {
            properties.load(input);
        }
        String agentId = properties.getProperty(ID_KEY);
        String createdAt = properties.getProperty(CREATED_AT_KEY);
        if (agentId == null || agentId.isBlank() || createdAt == null || createdAt.isBlank()) {
            throw new IOException("Agent identity file is missing agent.id or agent.created-at: " + identityFile);
        }
        try {
            UUID.fromString(agentId);
            java.time.Instant.parse(createdAt);
        } catch (RuntimeException e) {
            throw new IOException("Agent identity file contains an invalid ID or creation time: " + identityFile, e);
        }
        return new AgentIdentity(agentId, createdAt);
    }
}

package com.pbl4.agent.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
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
            AgentIdentity existing = read(identityFile);
            if (isUuid(existing.agentId())) {
                return existing;
            }

            AgentIdentity updated = new AgentIdentity(UUID.randomUUID().toString(), existing.createdAt());
            storeAtomically(identityFile, updated);
            return updated;
        }

        Files.createDirectories(identityFile.getParent());
        AgentIdentity identity = new AgentIdentity(UUID.randomUUID().toString(), Instant.now().toString());
        Properties properties = toProperties(identity);
        try (var output = Files.newOutputStream(identityFile, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            properties.store(output, "PBL4 network agent identity");
            return identity;
        } catch (FileAlreadyExistsException e) {
            AgentIdentity existing = read(identityFile);
            if (isUuid(existing.agentId())) {
                return existing;
            }

            AgentIdentity updated = new AgentIdentity(UUID.randomUUID().toString(), existing.createdAt());
            storeAtomically(identityFile, updated);
            return updated;
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
            if (agentId.length() > 50) {
                throw new IllegalArgumentException("Agent ID exceeds the supported length");
            }
            Instant.parse(createdAt);
        } catch (RuntimeException e) {
            throw new IOException("Agent identity file contains an invalid ID or creation time: " + identityFile, e);
        }
        return new AgentIdentity(agentId, createdAt);
    }

    private static boolean isUuid(String agentId) {
        if (agentId == null) {
            return false;
        }
        try {
            return UUID.fromString(agentId).toString().equals(agentId);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Properties toProperties(AgentIdentity identity) {
        Properties properties = new Properties();
        properties.setProperty(ID_KEY, identity.agentId());
        properties.setProperty(CREATED_AT_KEY, identity.createdAt());
        return properties;
    }

    private static void storeAtomically(Path identityFile, AgentIdentity identity) throws IOException {
        Path temporaryFile = Files.createTempFile(identityFile.getParent(), "identity-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporaryFile)) {
                toProperties(identity).store(output, "PBL4 network agent identity");
            }
            try {
                Files.move(temporaryFile, identityFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, identityFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }
}

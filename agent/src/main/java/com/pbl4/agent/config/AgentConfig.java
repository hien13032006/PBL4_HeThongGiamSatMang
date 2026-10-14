package com.pbl4.agent.config;

import java.util.Properties;

public class AgentConfig {
    private static final String DEFAULT_RESOURCE = "/agent-config.properties";
    private final Properties properties = new Properties();

    public AgentConfig() {
        this(DEFAULT_RESOURCE);
    }

    public AgentConfig(String resourcePath) {
        load(resourcePath);
    }

    private void load(String resourcePath) {
        try (var input = getClass().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Missing config resource: " + resourcePath);
            }
            properties.load(input);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load configuration from classpath: " + resourcePath, e);
        }
    }

    public String getString(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)));
    }

    public String getServerHost() {
        return getString("server.host", "127.0.0.1");
    }

    public int getServerPort() {
        int port = getInt("server.port", 9999);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("server.port must be between 1 and 65535");
        }
        return port;
    }

    public int getCollectIntervalMs() {
        return positiveInt("collect.interval.ms", 1000);
    }

    public int getReconnectDelayMs() {
        return positiveInt("reconnect.delay.ms", 3000);
    }

    private int positiveInt(String key, int defaultValue) {
        int value = getInt(key, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero");
        }
        return value;
    }

    public String getPingTarget() {
        return getString("ping.target", "8.8.8.8");
    }
}

package com.pbl4.agent.socket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class MockServer {
    private static final Logger log = LoggerFactory.getLogger(MockServer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        int port = 9999;
        log.info("Starting MockServer on port {}", port);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            long messageNumber = 0;
            while (true) {
                try (Socket socket = serverSocket.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                    log.info("Client connected from {}", socket.getRemoteSocketAddress());
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.isBlank()) {
                            continue;
                        }
                        messageNumber++;
                        try {
                            log.info("{}", formatMessage(messageNumber, socket.getRemoteSocketAddress().toString(), line));
                        } catch (JsonProcessingException e) {
                            log.warn("Received invalid JSON message #{} from {}: {}",
                                    messageNumber, socket.getRemoteSocketAddress(), line, e);
                        }
                    }
                } catch (IOException e) {
                    log.error("Connection error", e);
                }
            }
        } catch (IOException e) {
            log.error("Could not start MockServer on port {}", port, e);
        }
    }

    static String formatMessage(long messageNumber, String remoteAddress, String json) throws JsonProcessingException {
        JsonNode message = objectMapper.readTree(json);
        String messageType;
        if (message.has("hostname")) {
            messageType = "AGENT REGISTRATION";
        } else if ("heartbeat".equals(message.path("type").asText())) {
            messageType = "HEARTBEAT";
        } else if (message.has("rxRate") || message.has("txRate")) {
            messageType = "METRICS";
        } else {
            messageType = "UNKNOWN MESSAGE";
        }

        String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(message);
        return String.format("========== Message #%d | %s | %s ==========%n%s",
                messageNumber, messageType, remoteAddress, prettyJson);
    }
}

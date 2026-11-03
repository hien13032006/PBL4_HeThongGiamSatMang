package com.pbl4.agent.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class IcmpPingProbe {
    private static final Pattern RTT_PATTERN = Pattern.compile(
            "\\btime\\s*([=<])\\s*([0-9]+(?:[.,][0-9]+)?)\\s*ms\\b",
            Pattern.CASE_INSENSITIVE);

    private IcmpPingProbe() {
    }

    static Double measure(String target, int timeoutMs) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(buildCommand(target, timeoutMs))
                .redirectErrorStream(true)
                .start();

        long processTimeoutMs = Math.max(timeoutMs + 1_500L, 2_000L);
        boolean completed;
        try {
            completed = process.waitFor(processTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
        if (!completed) {
            process.destroyForcibly();
            process.waitFor();
            return null;
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return parseRttMillis(output);
    }

    private static List<String> buildCommand(String target, int timeoutMs) throws IOException {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = new ArrayList<>();
        command.add("ping");

        if (osName.contains("win")) {
            command.addAll(List.of("-n", "1", "-w", Integer.toString(timeoutMs), target));
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            command.addAll(List.of("-n", "-c", "1", "-W", Integer.toString(timeoutMs), target));
        } else if (osName.contains("linux")) {
            int timeoutSeconds = Math.max(1, (timeoutMs + 999) / 1_000);
            command.addAll(List.of("-n", "-c", "1", "-W", Integer.toString(timeoutSeconds), target));
        } else {
            throw new IOException("ICMP ping is not configured for operating system: " + osName);
        }

        return command;
    }

    static Double parseRttMillis(String output) {
        Matcher matcher = RTT_PATTERN.matcher(output);
        if (!matcher.find()) {
            return null;
        }

        double value = Double.parseDouble(matcher.group(2).replace(',', '.'));
        return matcher.group(1).equals("<") ? value / 2.0 : value;
    }
}

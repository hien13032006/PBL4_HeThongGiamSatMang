package com.pbl4.agent.util;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HostInfo {
    private static final Logger log = LoggerFactory.getLogger(HostInfo.class);
    private static final Pattern WINDOWS_SPEED = Pattern.compile(
            "^\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(bps|kbps|mbps|gbps|tbps)\\s*$",
            Pattern.CASE_INSENSITIVE);

    public static String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    public static NetworkDetails getPrimaryNetworkDetails() {
        try {
            InetAddress primaryAddress = InetAddress.getLocalHost();
            NetworkInterface primaryInterface = NetworkInterface.getByInetAddress(primaryAddress);
            if (primaryInterface != null) {
                return details(primaryInterface, primaryAddress);
            }
        } catch (UnknownHostException | SocketException e) {
            log.warn("Unable to resolve the primary network interface; trying active IPv4 interfaces", e);
        }

        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                    var addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (address instanceof java.net.Inet4Address) {
                            return details(networkInterface, address);
                        }
                    }
                }
            }
        } catch (SocketException e) {
            log.warn("Unable to enumerate network interfaces", e);
        }
        return new NetworkDetails(null, null, null, null);
    }

    private static NetworkDetails details(NetworkInterface networkInterface, InetAddress address) {
        String macAddress = null;
        try {
            byte[] macBytes = networkInterface.getHardwareAddress();
            if (macBytes != null) {
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < macBytes.length; i++) {
                    builder.append(String.format("%02X%s", macBytes[i], (i < macBytes.length - 1) ? ":" : ""));
                }
                macAddress = builder.toString();
            }
        } catch (SocketException e) {
            log.warn("Unable to read MAC address for interface {}", networkInterface.getName(), e);
        }
        return new NetworkDetails(
                address.getHostAddress(),
                macAddress,
                networkInterface.getName(),
                getMaxSpeedMbps(networkInterface)
        );
    }

    private static Long getMaxSpeedMbps(NetworkInterface networkInterface) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("linux")) {
            Path speedFile = Path.of("/sys/class/net", networkInterface.getName(), "speed");
            try {
                long speed = Long.parseLong(Files.readString(speedFile).trim());
                return speed > 0 ? speed : null;
            } catch (IOException | NumberFormatException e) {
                log.warn("Unable to read link speed for network interface {}", networkInterface.getName(), e);
                return null;
            }
        }

        if (osName.contains("win")) {
            return getWindowsMaxSpeedMbps(networkInterface.getIndex(), networkInterface.getName());
        }

        log.warn("Maximum link speed lookup is unsupported on this operating system: {}", osName);
        return null;
    }

    private static Long getWindowsMaxSpeedMbps(int interfaceIndex, String interfaceName) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "(Get-NetAdapter -InterfaceIndex " + interfaceIndex + " -ErrorAction Stop).LinkSpeed"
            ).redirectErrorStream(true).start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Timed out reading link speed for network interface {}", interfaceName);
                return null;
            }

            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (process.exitValue() != 0) {
                log.warn("Unable to read link speed for network interface {}: {}", interfaceName, output);
                return null;
            }
            return parseWindowsSpeedMbps(output);
        } catch (IOException e) {
            log.warn("Unable to read link speed for network interface {}", interfaceName, e);
            return null;
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            log.warn("Interrupted while reading link speed for network interface {}", interfaceName, e);
            return null;
        }
    }

    static Long parseWindowsSpeedMbps(String output) {
        Matcher matcher = WINDOWS_SPEED.matcher(output);
        if (!matcher.matches()) {
            log.warn("Unrecognized network interface link speed: {}", output);
            return null;
        }

        BigDecimal value = new BigDecimal(matcher.group(1).replace(',', '.'));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        int decimalShift = switch (unit) {
            case "bps" -> -6;
            case "kbps" -> -3;
            case "mbps" -> 0;
            case "gbps" -> 3;
            case "tbps" -> 6;
            default -> throw new IllegalStateException("Unsupported link speed unit: " + unit);
        };
        return value.movePointRight(decimalShift).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    public static String getOsName() {
        return System.getProperty("os.name", "unknown");
    }

    public static List<String> getNetworkInterfaces() {
        List<String> interfaces = new ArrayList<>();
        try {
            var en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                var nic = en.nextElement();
                if (nic.isUp() && nic.getInterfaceAddresses() != null && !nic.isLoopback()) {
                    interfaces.add(nic.getName());
                }
            }
        } catch (SocketException e) {
            interfaces.add("unknown");
        }
        return interfaces;
    }

    public record NetworkDetails(String ipAddress, String macAddress, String nicName, Long maxSpeedMbps) {
    }
}

package com.pbl4.agent.util;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HostInfo {
    private static final Logger log = LoggerFactory.getLogger(HostInfo.class);

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
        return new NetworkDetails(null, null, null);
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
        return new NetworkDetails(address.getHostAddress(), macAddress, networkInterface.getName());
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

    public record NetworkDetails(String ipAddress, String macAddress, String nicName) {
    }
}

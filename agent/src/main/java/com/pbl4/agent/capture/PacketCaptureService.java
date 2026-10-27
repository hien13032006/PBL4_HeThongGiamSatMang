package com.pbl4.agent.capture;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.WString;
import org.pcap4j.core.*;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.IpV6Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class PacketCaptureService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PacketCaptureService.class);

    private final AtomicLong rxBytes = new AtomicLong();
    private final AtomicLong txBytes = new AtomicLong();
    private final AtomicLong totalPackets = new AtomicLong();
    private final AtomicLong rxPackets = new AtomicLong();
    private final AtomicLong txPackets = new AtomicLong();
    private final Set<InetAddress> localAddresses = ConcurrentHashMap.newKeySet();
    private final List<PcapHandle> handles = new ArrayList<>();
    private volatile boolean running = true;

    public List<String> listNetworkInterfaces() {
        List<String> names = new ArrayList<>();
        try {
            for (PcapNetworkInterface device : Pcaps.findAllDevs()) {
                names.add(device.getName());
            }
        } catch (PcapNativeException | UnsatisfiedLinkError e) {
            log.warn("Unable to list network devices because native packet capture support is unavailable on this host.", e);
        }
        return names;
    }

    public void start() {
        try {
            configureNpcapLibraryPath();

            for (PcapNetworkInterface device : Pcaps.findAllDevs()) {
                var addresses = device.getAddresses().stream()
                        .map(PcapAddress::getAddress)
                        .toList();
                if (addresses.isEmpty()) {
                    continue;
                }

                localAddresses.addAll(addresses);
                try {
                    PcapHandle handle = device.openLive(65536, PcapNetworkInterface.PromiscuousMode.PROMISCUOUS, 10);
                    handles.add(handle);
                    Thread captureThread = new Thread(() -> captureLoop(handle), "packet-capture-" + device.getName());
                    captureThread.setDaemon(true);
                    captureThread.start();
                    log.info("Started packet capture on interface {}", device.getName());
                } catch (PcapNativeException | UnsatisfiedLinkError e) {
                    log.warn("Unable to start packet capture on interface {}", device.getName(), e);
                }
            }
            if (handles.isEmpty()) {
                log.warn("No network interface was available for packet capture.");
            }
        } catch (PcapNativeException | UnsatisfiedLinkError e) {
            log.warn("Pcap4J cannot open the network interface. This is expected on machines without Npcap/libpcap support; continuing without packet capture.", e);
        }
    }

    private void configureNpcapLibraryPath() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }

        String[] candidateDirs = {
                System.getenv("NPCAP_HOME"),
                System.getenv("NPCAP_ROOT"),
                "C:\\Windows\\System32\\Npcap",
                "C:\\Windows\\SysWOW64\\Npcap",
                "C:\\Program Files\\Npcap"
        };

        for (String dir : candidateDirs) {
            if (dir == null || dir.isBlank()) {
                continue;
            }

            Path libraryDir = Path.of(dir);
            if (!Files.isRegularFile(libraryDir.resolve("wpcap.dll"))
                    || !Files.isRegularFile(libraryDir.resolve("Packet.dll"))) {
                continue;
            }

            try {
                Function setDllDirectory = NativeLibrary.getInstance("kernel32")
                        .getFunction("SetDllDirectoryW", Function.ALT_CONVENTION);
                int result = setDllDirectory.invokeInt(new Object[]{new WString(libraryDir.toString())});
                if (result == 0) {
                    log.warn("Windows rejected the Npcap DLL directory {} (error {}).",
                            libraryDir, Native.getLastError());
                    continue;
                }

                String path = libraryDir.toString();
                System.setProperty("jna.library.path", path);
                NativeLibrary.addSearchPath("wpcap", path);
                NativeLibrary.addSearchPath("Packet", path);
                log.info("Configured Windows and JNA DLL search paths for Npcap: {}", path);
                return;
            } catch (UnsatisfiedLinkError | RuntimeException e) {
                log.warn("Unable to configure Windows DLL search path for Npcap at {}.", libraryDir, e);
            }
        }

        log.warn("Npcap DLLs were not found in the standard directories or NPCAP_HOME/NPCAP_ROOT.");
    }

    private void captureLoop(PcapHandle handle) {
        try {
            while (running && handle.isOpen()) {
                Packet packet = handle.getNextPacket();
                if (packet == null) {
                    continue;
                }

                if (packet.contains(IpV4Packet.class)) {
                    var ip = packet.get(IpV4Packet.class);
                    recordPacket(packet, ip.getHeader().getSrcAddr(), ip.getHeader().getDstAddr());
                } else if (packet.contains(IpV6Packet.class)) {
                    var ip = packet.get(IpV6Packet.class);
                    recordPacket(packet, ip.getHeader().getSrcAddr(), ip.getHeader().getDstAddr());
                }
            }
        } catch (Exception e) {
            log.warn("Packet capture loop ended unexpectedly; continuing without live packet tracing.", e);
        }
    }

    private void recordPacket(Packet packet, InetAddress source, InetAddress destination) {
        byte[] data = packet.getRawData();
        long packetLength = data == null ? 0 : data.length;
        boolean incoming = localAddresses.contains(destination);
        boolean outgoing = localAddresses.contains(source);
        if (!incoming && !outgoing) {
            return;
        }

        totalPackets.incrementAndGet();
        if (incoming) {
            rxBytes.addAndGet(packetLength);
            rxPackets.incrementAndGet();
        }
        if (outgoing) {
            txBytes.addAndGet(packetLength);
            txPackets.incrementAndGet();
        }
    }

    public long getRxBytes() {
        return rxBytes.get();
    }

    public long getTxBytes() {
        return txBytes.get();
    }

    public long getTotalPackets() {
        return totalPackets.get();
    }

    public long getRxPackets() {
        return rxPackets.get();
    }

    public long getTxPackets() {
        return txPackets.get();
    }

    public void stop() {
        running = false;
        for (PcapHandle handle : handles) {
            try {
                handle.close();
            } catch (Exception e) {
                log.debug("Failed to close packet handle", e);
            }
        }
    }

    @Override
    public void close() {
        stop();
    }
}

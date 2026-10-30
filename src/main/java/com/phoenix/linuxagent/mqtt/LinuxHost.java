// SPDX-License-Identifier: Apache-2.0

package com.phoenix.linuxagent.mqtt;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What this Linux machine is and what it has free, read from {@code /proc}.
 *
 * <p>Nothing here is invented: a value the kernel does not provide is
 * reported as unknown ({@code -1} for CPU usage) or makes the agent refuse
 * to start (total memory, which Core requires).
 */
public class LinuxHost implements MqttNodeAgent.Host {

    /** The device id file the HTTP agent has always used, so both transports share one identity. */
    public static final Path DEFAULT_DEVICE_ID_FILE =
            Paths.get(System.getProperty("user.home"), ".phoenix_linux_device_id");

    /** Protocol rule for a device id (it is also the broker user name). */
    public static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private static final Path MEMINFO = Paths.get("/proc/meminfo");
    private static final Path STAT = Paths.get("/proc/stat");

    private final CpuSampler cpu = new CpuSampler();

    /**
     * Reads the device id from {@code file}, creating it ({@code linux-<uuid>})
     * on first use.
     *
     * @throws IOException if the file cannot be read or written
     * @throws IllegalStateException if the file holds an unusable id
     */
    public static String loadOrCreateDeviceId(Path file) throws IOException {
        if (Files.exists(file)) {
            String id = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (!DEVICE_ID.matcher(id).matches()) {
                throw new IllegalStateException("The device id in " + file + " (\"" + id
                        + "\") is not usable: 1-128 characters of [A-Za-z0-9._-], starting with a"
                        + " letter or digit.");
            }
            return id;
        }
        String id = "linux-" + UUID.randomUUID();
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, (id + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        return id;
    }

    /** The host name, as the HTTP agent reports it. */
    public String hostName() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (IOException | RuntimeException e) {
            // fall through
        }
        try {
            String name = new String(Files.readAllBytes(Paths.get("/etc/hostname")), StandardCharsets.UTF_8).trim();
            if (!name.isEmpty()) {
                return name;
            }
        } catch (IOException | RuntimeException e) {
            // fall through
        }
        return "linux-node";
    }

    /** The JVM's name for the CPU architecture ({@code amd64}, {@code aarch64}, {@code arm} ...). */
    public String architecture() {
        String arch = System.getProperty("os.arch");
        return arch == null || arch.isBlank() ? "unknown" : arch;
    }

    public int cpuCores() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * Total memory in MB.
     *
     * @throws IllegalStateException if {@code /proc/meminfo} cannot be read:
     *         this agent needs Linux
     */
    public long totalRamMb() {
        long kb = meminfoKb(readOrNull(MEMINFO), "MemTotal");
        if (kb <= 0) {
            throw new IllegalStateException("Cannot read MemTotal from /proc/meminfo; this agent needs Linux.");
        }
        return kb / 1024;
    }

    /** Memory available for new work, in MB; 0 if it cannot be read. */
    public long availableRamMb() {
        return availableRamMb(readOrNull(MEMINFO));
    }

    /**
     * CPU utilization in percent since the previous call (the first call
     * covers the time since this object was created), or {@code -1} if the
     * kernel's counters cannot be read.
     */
    public double cpuUsagePercent() {
        return cpu.usagePercent(readOrNull(STAT));
    }

    // ------------------------------------------------------------------
    // parsing, separate so tests can feed sample files
    // ------------------------------------------------------------------

    /**
     * {@code MemAvailable}, or on kernels older than 3.14 that lack it, the
     * classic estimate {@code MemFree + Buffers + Cached}. 0 if unreadable.
     */
    static long availableRamMb(String meminfo) {
        long available = meminfoKb(meminfo, "MemAvailable");
        if (available >= 0) {
            return available / 1024;
        }
        long free = meminfoKb(meminfo, "MemFree");
        if (free < 0) {
            return 0;
        }
        long buffers = Math.max(0, meminfoKb(meminfo, "Buffers"));
        long cached = Math.max(0, meminfoKb(meminfo, "Cached"));
        return (free + buffers + cached) / 1024;
    }

    /** The value of {@code field} in kB, or -1 if absent. */
    static long meminfoKb(String meminfo, String field) {
        if (meminfo == null) {
            return -1;
        }
        for (String line : meminfo.split("\n")) {
            if (line.startsWith(field + ":")) {
                String[] parts = line.substring(field.length() + 1).trim().split("\\s+");
                try {
                    return Long.parseLong(parts[0]);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * Busy and total jiffies from the aggregate {@code cpu} line of
     * {@code /proc/stat}, counting idle and iowait as idle; null if unreadable.
     */
    static long[] cpuCounters(String stat) {
        if (stat == null) {
            return null;
        }
        String line = stat.split("\n", 2)[0];
        if (!line.startsWith("cpu ")) {
            return null;
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 5) {
            return null;
        }
        long total = 0;
        long idle = 0;
        try {
            // user nice system idle iowait irq softirq steal (guest time is
            // already included in user and nice, so it is not added again)
            for (int i = 1; i < parts.length && i <= 8; i++) {
                long value = Long.parseLong(parts[i]);
                total += value;
                if (i == 4 || i == 5) {
                    idle += value;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return new long[] {total - idle, total};
    }

    /** Utilization between two counter samples, or -1 if it cannot be known. */
    static double usagePercent(long[] previous, long[] current) {
        if (previous == null || current == null) {
            return -1.0;
        }
        long total = current[1] - previous[1];
        long busy = current[0] - previous[0];
        if (total <= 0 || busy < 0) {
            return -1.0;
        }
        double percent = 100.0 * busy / total;
        return Math.round(Math.min(100.0, percent) * 10.0) / 10.0;
    }

    private static String readOrNull(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Remembers the last counters so each reading covers the time since the previous one. */
    static final class CpuSampler {

        private long[] last = cpuCounters(readOrNull(STAT));

        synchronized double usagePercent(String stat) {
            long[] now = cpuCounters(stat);
            double usage = LinuxHost.usagePercent(last, now);
            if (now != null) {
                last = now;
            }
            return usage;
        }
    }
}

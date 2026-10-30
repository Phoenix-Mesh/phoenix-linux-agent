// SPDX-License-Identifier: Apache-2.0

package com.phoenix.linuxagent.mqtt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxHostTest {

    private static final String MEMINFO = String.join("\n",
            "MemTotal:       16303404 kB",
            "MemFree:         1024000 kB",
            "MemAvailable:    8192000 kB",
            "Buffers:          204800 kB",
            "Cached:          2048000 kB",
            "");

    /** A kernel older than 3.14 has no MemAvailable. */
    private static final String OLD_MEMINFO = String.join("\n",
            "MemTotal:        1015808 kB",
            "MemFree:          102400 kB",
            "Buffers:           51200 kB",
            "Cached:           256000 kB",
            "");

    @Test
    void readsMemoryFromMeminfo() {
        assertEquals(16303404, LinuxHost.meminfoKb(MEMINFO, "MemTotal"));
        assertEquals(8000, LinuxHost.availableRamMb(MEMINFO));
        assertEquals(-1, LinuxHost.meminfoKb(MEMINFO, "Nope"));
        assertEquals(-1, LinuxHost.meminfoKb(null, "MemTotal"));
    }

    @Test
    void estimatesAvailableMemoryOnOldKernels() {
        assertEquals((102400 + 51200 + 256000) / 1024, LinuxHost.availableRamMb(OLD_MEMINFO));
        assertEquals(0, LinuxHost.availableRamMb("garbage"));
        assertEquals(0, LinuxHost.availableRamMb(null));
    }

    @Test
    void readsCpuCountersCountingIowaitAsIdle() {
        // user nice system idle iowait irq softirq steal guest guest_nice
        long[] counters = LinuxHost.cpuCounters("cpu  100 10 50 800 40 0 0 0 5 0\ncpu0 1 2 3 4\n");
        assertArrayEquals(new long[] {160, 1000}, counters);

        // Very old kernels list only four values.
        assertArrayEquals(new long[] {160, 960}, LinuxHost.cpuCounters("cpu 100 10 50 800\n"));

        assertNull(LinuxHost.cpuCounters("intr 1 2 3"));
        assertNull(LinuxHost.cpuCounters("cpu 1 2"));
        assertNull(LinuxHost.cpuCounters("cpu a b c d"));
        assertNull(LinuxHost.cpuCounters(null));
    }

    @Test
    void cpuUsageIsTheBusyShareBetweenTwoSamplesOrUnknown() {
        assertEquals(25.0, LinuxHost.usagePercent(new long[] {100, 1000}, new long[] {150, 1200}));
        assertEquals(-1.0, LinuxHost.usagePercent(null, new long[] {1, 2}));
        assertEquals(-1.0, LinuxHost.usagePercent(new long[] {1, 2}, null));
        assertEquals(-1.0, LinuxHost.usagePercent(new long[] {5, 10}, new long[] {5, 10}));
    }

    @Test
    void theDeviceIdIsCreatedOnceAndKept(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("state").resolve("device-id");

        String first = LinuxHost.loadOrCreateDeviceId(file);
        String second = LinuxHost.loadOrCreateDeviceId(file);

        assertTrue(first.startsWith("linux-"));
        assertTrue(LinuxHost.DEVICE_ID.matcher(first).matches());
        assertEquals(first, second);
    }

    @Test
    void anExistingHttpAgentIdIsReused(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".phoenix_linux_device_id");
        Files.write(file, "linux-0b6f3f0e-1111-2222-3333-444455556666".getBytes(StandardCharsets.UTF_8));

        assertEquals("linux-0b6f3f0e-1111-2222-3333-444455556666", LinuxHost.loadOrCreateDeviceId(file));
    }

    @Test
    void anUnusableStoredIdIsRefused(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("id");
        Files.write(file, "has spaces/and+wildcards#".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class, () -> LinuxHost.loadOrCreateDeviceId(file));
    }

    @Test
    void thisMachineReportsRealValues() {
        LinuxHost host = new LinuxHost();
        if (!Files.exists(Path.of("/proc/meminfo"))) {
            return; // not Linux
        }
        assertTrue(host.totalRamMb() > 0);
        assertTrue(host.availableRamMb() > 0);
        assertTrue(host.cpuCores() > 0);
        double cpu = host.cpuUsagePercent();
        assertTrue(cpu == -1.0 || (cpu >= 0 && cpu <= 100), String.valueOf(cpu));
        assertTrue(!host.hostName().isBlank());
    }
}

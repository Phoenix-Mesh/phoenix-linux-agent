// SPDX-License-Identifier: Apache-2.0

package com.phoenix.linuxagent;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class LinuxNodeAgent {

    private static final Path DEVICE_ID_FILE =
            Paths.get(System.getProperty("user.home"), ".phoenix_linux_device_id");

    private final String coreUrl;

    private final int callbackPort;

    private final String deviceId;

    // ADDED: Number of tasks currently being executed by this agent.
    private final AtomicInteger activeTasks =
            new AtomicInteger(0);

    // ADDED: RTT measured from the most recently completed heartbeat.
    //
    // The current heartbeat cannot contain its own RTT because that value
    // is only known after Phoenix responds. Therefore, the measured RTT
    // is carried in the following heartbeat.
    private volatile double lastHeartbeatRttMs =
            0.0;

    // Protocol features announced to Phoenix Core at registration.
    //
    // "attempt-id": this agent receives the opaque attemptId Core sends with
    // each task and echoes it back verbatim. Core uses that to fence late or
    // duplicate reports from an earlier attempt of the same task. An agent
    // that does not announce it is treated by Core as LEGACY (unfenced).
    //
    // Only announce a feature this class really implements: Core starts
    // sending attemptId/attemptNumber to this node as soon as it is announced.
    private static final String PROTOCOL_FEATURES =
            "[\"attempt-id\"]";

    // ADDED: Explicit heuristic energy-cost estimate.
    //
    // This is intentionally a heuristic and not a claim of instantaneous
    // physical power consumption.
    private static final double ENERGY_COST_ESTIMATE = 4.5;

    public LinuxNodeAgent(String coreUrl, int callbackPort) throws IOException {

        this.coreUrl = coreUrl;

        this.callbackPort = callbackPort;

        this.deviceId = loadOrCreateDeviceId();
    }

    public static void main(String[] args) throws Exception {

        String coreUrl = args.length > 0 ? args[0] : "http://127.0.0.1:8080";

        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9090;

        new LinuxNodeAgent(coreUrl, port).start();
    }

    private void start() throws Exception {

        startCallbackServer();

        register();

        Executors.newSingleThreadScheduledExecutor(r -> {

            Thread t = new Thread(r, "linux-agent-heartbeat");

            t.setDaemon(true);

            return t;

        }).scheduleAtFixedRate(this::heartbeat, 0, 5, TimeUnit.SECONDS);

        System.out.println("[LinuxNodeAgent] Running, listening for pushed tasks on port "
                + callbackPort + ". Ctrl+C to stop.");

        Thread.currentThread().join();
    }

    private void startCallbackServer() throws IOException {

        HttpServer server =
                HttpServer.create(
                        new InetSocketAddress(callbackPort),
                        0
                );

        server.createContext("/execute", this::handleExecute);

        server.setExecutor(null);

        server.start();
    }

    private void handleExecute(HttpExchange exchange) throws IOException {

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {

            exchange.sendResponseHeaders(405, -1);

            return;
        }

        String body =
                new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                );

        // Prefer taskId (standardized), fall back to id for resilience.
        String taskId = extractString(body, "taskId");

        if (taskId.isBlank()) taskId = extractString(body, "id");

        long samples =
                extractLong(body, "samples", 2_000_000);

        // Attempt identity (only sent by Core to nodes that announced
        // "attempt-id"). Kept in its raw, still JSON-escaped form so it can
        // be echoed back verbatim, as the protocol requires. Empty when the
        // Core is older than this agent and sends none.
        String attemptId =
                extractRawString(body, "attemptId");

        // Informational only (logs); never used to identify an attempt.
        long attemptNumber =
                extractLong(body, "attemptNumber", -1);

        System.out.printf(
                "[LinuxNodeAgent] Received task %s%s, executing Monte-Carlo (samples=%d)...%n",
                taskId,
                attemptId.isEmpty()
                        ? ""
                        : " (attempt " + attemptNumber
                                + ", id " + shortId(attemptId) + ")",
                samples
        );

        // ADDED:
        // Mark the task as active before actual computation begins.
        activeTasks.incrementAndGet();

        try {

            long start =
                    System.nanoTime();

            long inside = 0;

            for (long i = 0; i < samples; i++) {

                double x = Math.random(), y = Math.random();

                if (x * x + y * y <= 1.0) inside++;
            }

            double pi =
                    4.0 * inside / samples;

            long elapsedMs =
                    (System.nanoTime() - start) / 1_000_000;

            String output =
                    "Computed Monte-Carlo pi ~= " + pi;

            System.out.printf(
                    "[LinuxNodeAgent] Task %s done: %s (%dms)%n",
                    taskId,
                    output,
                    elapsedMs
            );

            // Echo the attempt id verbatim. For this push transport the
            // result is the response to the very request that carried the
            // task, so the echo is not needed for fencing; it keeps the
            // agent's behaviour uniform with the pull agents and makes the
            // attempt visible in traces.
            String response =
                    "{\"status\":\"SUCCESS\",\"outputData\":\""
                            + escape(output)
                            + "\",\"executionTimeMs\":"
                            + elapsedMs
                            + (attemptId.isEmpty()
                                    ? ""
                                    : ",\"attemptId\":\"" + attemptId + "\"")
                            + "}";

            byte[] bytes =
                    response.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders()
                    .set("Content-Type", "application/json");

            exchange.sendResponseHeaders(
                    200,
                    bytes.length
            );

            try (OutputStream os =
                         exchange.getResponseBody()) {

                os.write(bytes);
            }

        } finally {

            // ADDED:
            // Ensure the active-task count is restored even if execution
            // or response handling throws an exception.
            activeTasks.decrementAndGet();
        }
    }

    private void register() throws IOException {

        String payload = "{"
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"displayName\":\"" + escape(hostname()) + "\","
                + "\"hardwareClass\":\"LINUX\","
                + "\"architecture\":\"" + escape(System.getProperty("os.arch")) + "\","
                + "\"cpuCores\":" + Runtime.getRuntime().availableProcessors() + ","
                + "\"memoryMb\":" + totalMemoryMb() + ","
                + "\"adapterType\":\"linux\","
                + "\"capabilities\":[],"
                + "\"protocolFeatures\":" + PROTOCOL_FEATURES + ","
                + "\"callbackPort\":" + callbackPort
                + "}";

        System.out.println(
                "[LinuxNodeAgent] Registered: "
                        + post("/register", payload)
        );
    }

    private void heartbeat() {

        try {

            // ADDED:
            // Read the current CPU utilization.
            double cpu =
                    getCpuUsagePercent();

            // ADDED:
            // Read currently available RAM.
            long availableRam =
                    getAvailableRamMb();

            // ADDED:
            // Snapshot the number of currently executing tasks.
            int currentActiveTasks =
                    activeTasks.get();

            // ADDED:
            // There is currently no local task queue.
            int queueLength = 0;

            // ADDED:
            // This is the RTT measured from the PREVIOUS heartbeat.
            //
            // The RTT of this heartbeat is not known until Phoenix
            // responds, so it cannot be included in this same payload.
            double networkLatency =
                    lastHeartbeatRttMs;

            // ADDED:
            // Explicit heuristic rather than fake real-time energy data.
            double energyCost =
                    ENERGY_COST_ESTIMATE;

            String payload =
                    "{\"deviceId\":\"" + deviceId + "\","
                            + "\"cpuUsagePercent\":" + cpu + ","
                            + "\"availableRamMb\":" + availableRam + ","
                            + "\"activeTasks\":" + currentActiveTasks + ","
                            + "\"queueLength\":" + queueLength + ","
                            + "\"networkLatencyMs\":" + networkLatency + ","
                            + "\"energyCostEstimate\":" + energyCost
                            + "}";

            /*
             * Measure the complete HTTP round trip:
             *
             * Agent                         Phoenix
             *   |                              |
             *   |------ POST /heartbeat ------>|
             *   |                              |
             *   |<--------- response ----------|
             *   |                              |
             *   +------ measured RTT ----------+
             *
             * The resulting value becomes lastHeartbeatRttMs and is
             * reported in the NEXT heartbeat.
             */
            long start =
                    System.nanoTime();

            post("/heartbeat", payload);

            lastHeartbeatRttMs =
                    (System.nanoTime() - start)
                            / 1_000_000.0;

            System.out.printf(
                    "[LinuxNodeAgent] Heartbeat sent: "
                            + "cpu=%.1f%% "
                            + "freeRam=%dMB "
                            + "activeTasks=%d "
                            + "rtt=%.1fms "
                            + "energy=%.1f%n",
                    cpu,
                    availableRam,
                    currentActiveTasks,
                    lastHeartbeatRttMs,
                    energyCost
            );

        } catch (IOException e) {

            System.out.println(
                    "[LinuxNodeAgent] Heartbeat failed: "
                            + e.getMessage()
            );

        } catch (Exception e) {

            // ADDED:
            // Prevent an unexpected telemetry failure from killing the
            // scheduled heartbeat task.
            System.out.println(
                    "[LinuxNodeAgent] Heartbeat telemetry failed: "
                            + e.getMessage()
            );
        }
    }

    // ADDED:
    //
    // Calculate CPU utilization by sampling Linux's aggregate CPU
    // counters twice and calculating the amount of time spent working
    // versus the total amount of elapsed CPU time.
    private double getCpuUsagePercent() {

        try {

            long[] first =
                    readCpuCounters();

            Thread.sleep(200);

            long[] second =
                    readCpuCounters();

            long idleDelta =
                    second[3] - first[3];

            long totalDelta =
                    second[0] - first[0]
                            + second[1] - first[1]
                            + second[2] - first[2]
                            + second[3] - first[3];

            if (totalDelta <= 0) {
                return -1.0;
            }

            return (1.0
                    - ((double) idleDelta / totalDelta))
                    * 100.0;

        } catch (Exception e) {

            // -1 means CPU utilization is unknown.
            // Phoenix already handles unknown CPU values.
            return -1.0;
        }
    }

    // ADDED:
    //
    // Read the aggregate CPU counters from /proc/stat.
    //
    // The first line looks approximately like:
    //
    // cpu  user nice system idle ...
    //
    // We use the first four counters for this telemetry version.
    private long[] readCpuCounters() throws IOException {

        try (BufferedReader reader =
                     new BufferedReader(
                             new FileReader("/proc/stat"))) {

            String line =
                    reader.readLine();

            if (line == null
                    || !line.startsWith("cpu ")) {

                throw new IOException(
                        "Unable to read /proc/stat"
                );
            }

            String[] parts =
                    line.trim().split("\\s+");

            long user =
                    Long.parseLong(parts[1]);

            long nice =
                    Long.parseLong(parts[2]);

            long system =
                    Long.parseLong(parts[3]);

            long idle =
                    Long.parseLong(parts[4]);

            return new long[]{
                    user,
                    nice,
                    system,
                    idle
            };
        }
    }

    // ADDED:
    //
    // Read Linux's MemAvailable value from /proc/meminfo.
    private long getAvailableRamMb()
            throws IOException {

        try (BufferedReader reader =
                     new BufferedReader(
                             new FileReader("/proc/meminfo"))) {

            String line;

            while ((line = reader.readLine()) != null) {

                if (line.startsWith("MemAvailable:")) {

                    String[] parts =
                            line.trim().split("\\s+");

                    long kb =
                            Long.parseLong(parts[1]);

                    return kb / 1024;
                }
            }
        }

        throw new IOException(
                "MemAvailable not found"
        );
    }

    private String post(
            String path,
            String jsonBody) throws IOException {

        HttpURLConnection conn =
                (HttpURLConnection) new URL(coreUrl + path)
                        .openConnection();

        conn.setRequestMethod("POST");

        conn.setConnectTimeout(5000);

        conn.setReadTimeout(5000);

        conn.setRequestProperty(
                "Content-Type",
                "application/json"
        );

        conn.setDoOutput(true);

        try (OutputStream os =
                     conn.getOutputStream()) {

            os.write(
                    jsonBody.getBytes(
                            StandardCharsets.UTF_8
                    )
            );
        }

        int code =
                conn.getResponseCode();

        InputStream stream =
                code < 300
                        ? conn.getInputStream()
                        : conn.getErrorStream();

        String response =
                new String(
                        stream.readAllBytes(),
                        StandardCharsets.UTF_8
                );

        if (code >= 300) {

            throw new IOException(
                    "HTTP " + code + ": " + response
            );
        }

        return response;
    }

    private String loadOrCreateDeviceId()
            throws IOException {

        if (Files.exists(DEVICE_ID_FILE))
            return Files.readString(
                    DEVICE_ID_FILE
            ).trim();

        String id =
                "linux-" + UUID.randomUUID();

        Files.writeString(
                DEVICE_ID_FILE,
                id
        );

        return id;
    }

    private static String hostname() {

        try {

            return InetAddress
                    .getLocalHost()
                    .getHostName();

        } catch (Exception e) {

            return "linux-node";
        }
    }

    private static long totalMemoryMb() {

        return meminfoField("MemTotal");
    }

    private static long availableMemoryMb() {

        return meminfoField("MemAvailable");
    }

    private static long meminfoField(String field) {

        try {

            return Files.readAllLines(
                            Paths.get("/proc/meminfo")
                    )
                    .stream()
                    .filter(l -> l.startsWith(field))
                    .findFirst()
                    .map(l -> Long.parseLong(
                            l.replaceAll("\\D", "")
                    ) / 1024)
                    .orElse(0L);

        } catch (Exception e) {

            return 0;
        }
    }

    private static String extractString(
            String json,
            String key) {

        var m =
                Pattern.compile(
                        "\"" + key + "\"\\s*:\\s*\"([^\"]*)\""
                )
                .matcher(json);

        return m.find()
                ? m.group(1)
                : "";
    }

    /**
     * Like {@link #extractString}, but understands JSON escapes and returns
     * the value exactly as it appears between the quotes (still escaped), so
     * it can be written back into a JSON string unchanged.
     */
    private static String extractRawString(
            String json,
            String key) {

        var m =
                Pattern.compile(
                        "\"" + key
                                + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
                )
                .matcher(json);

        return m.find()
                ? m.group(1)
                : "";
    }

    /** First 8 characters, for log lines only. */
    private static String shortId(String id) {

        return id.length() <= 8
                ? id
                : id.substring(0, 8);
    }

    private static long extractLong(
            String json,
            String key,
            long fallback) {

        var m =
                Pattern.compile(
                        "\"" + key + "\"\\s*:\\s*(\\d+)"
                )
                .matcher(json);

        return m.find()
                ? Long.parseLong(m.group(1))
                : fallback;
    }

    private static String escape(String v) {

        return v == null
                ? ""
                : v.replace("\\", "\\\\")
                        .replace("\"", "\\\"");
    }
}

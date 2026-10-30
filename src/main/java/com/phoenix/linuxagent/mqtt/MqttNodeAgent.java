// SPDX-License-Identifier: Apache-2.0

package com.phoenix.linuxagent.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Phoenix node agent speaking the MQTT/TLS node-agent protocol, version 1
 * (Phoenix Core: {@code docs/mqtt-agent-protocol.md}).
 *
 * <ul>
 *     <li>Registers with a fresh {@code requestId} (and the enrollment token,
 *         if set), keeps the session token, and resends an unanswered request.</li>
 *     <li>Sends heartbeats with real telemetry and the session token. It never
 *         decides whether the node is ONLINE, DEGRADED or OFFLINE: Phoenix
 *         Core does, from these heartbeats, exactly as for every other node.</li>
 *     <li>For each task: acknowledges it at once, runs the Monte-Carlo-Pi
 *         workload, reports {@code started} and the result, always echoing
 *         the {@code attemptId}.</li>
 *     <li>Idempotent: a redelivered attempt is acknowledged again but never
 *         run twice (its known result is re-sent); cancelled attempts are
 *         skipped or stopped.</li>
 *     <li>Registers again when Core restarts (new {@code coreInstanceId}) or
 *         asks it to.</li>
 * </ul>
 *
 * <p>Recovery is not this class's business: an attempt that is interrupted
 * or cancelled sends no result, and Phoenix Core's timeout and recovery
 * policy decides what happens to the task.
 */
public final class MqttNodeAgent {

    public static final int PROTOCOL_VERSION = 1;
    public static final String TASK_TYPE = "Monte-Carlo-Pi";

    /** Fixed heuristic, the same as the HTTP agent's; not a measurement. */
    public static final double ENERGY_COST_ESTIMATE = 4.5;

    static final long DEFAULT_SAMPLES = 2_000_000L;
    static final long MAX_SAMPLES = 200_000_000L;

    private static final int QOS = 1;

    /** How many handled and cancelled attempt ids are remembered. */
    private static final int REMEMBERED_ATTEMPTS = 1_024;

    /** Tasks that arrive before the registration answer wait for it; at most this many. */
    private static final int MAX_EARLY_TASKS = 64;

    private static final String ONLINE = Json.object().put("state", "online").toString();
    private static final String OFFLINE = Json.object().put("state", "offline").toString();

    /** The machine the agent runs on; {@link LinuxHost} in production. */
    public interface Host {

        String hostName();

        String architecture();

        int cpuCores();

        long totalRamMb();

        long availableRamMb();

        /** Percent since the previous call, or -1 if unknown. */
        double cpuUsagePercent();
    }

    /** Timings; tests shorten them. */
    public static final class Timing {

        /** Resend an unanswered registration after this long. */
        final long registrationRetryMs;
        /** Heartbeat at least this often, even if Core allows longer. */
        final long maxHeartbeatIntervalSeconds;
        final int keepAliveSeconds;

        public Timing(long registrationRetryMs, long maxHeartbeatIntervalSeconds, int keepAliveSeconds) {
            this.registrationRetryMs = registrationRetryMs;
            this.maxHeartbeatIntervalSeconds = maxHeartbeatIntervalSeconds;
            this.keepAliveSeconds = keepAliveSeconds;
        }

        public static Timing defaults() {
            return new Timing(10_000, 5, 30);
        }
    }

    /** Topic names for this device (protocol version 1). */
    private static final class Topics {

        final String coreStatus;
        final String downAll;
        private final String node;

        Topics(String prefix, String deviceId) {
            this.coreStatus = prefix + "/v" + PROTOCOL_VERSION + "/core/status";
            this.node = prefix + "/v" + PROTOCOL_VERSION + "/nodes/" + deviceId;
            this.downAll = node + "/down/#";
        }

        String up(String kind) {
            return node + "/up/" + kind;
        }

        String down(String kind) {
            return node + "/down/" + kind;
        }
    }

    private final MqttAgentOptions options;
    private final String deviceId;
    private final Host host;
    private final Consumer<String> log;
    private final Function<MqttLink.Settings, MqttLink> linkFactory;
    private final MonteCarlo.Workload workload;
    private final Timing timing;
    private final Topics topics;

    private final Object lock = new Object();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(daemon("phoenix-mqtt-timer"));
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(daemon("phoenix-mqtt-worker"));

    // All guarded by lock.
    private MqttLink link;
    private boolean running;
    private Consumer<String> onFatal;

    private String sessionToken;
    private String nodeId;
    private String coreInstanceId;

    private String requestId;
    private String requestBody;
    private long requestSentAtNs;
    private boolean answered;
    private ScheduledFuture<?> retry;

    private ScheduledFuture<?> heartbeats;
    private long heartbeatIntervalSeconds;
    private double latencyMs;

    private int queued;
    private int active;

    private final Deque<Map<String, Object>> earlyTasks = new ArrayDeque<>();

    /** Attempt id to its result body, or null while it runs. Bounded, oldest first. */
    private final Map<String, Map<String, Object>> handled = bounded();

    /** Attempt ids Core cancelled; a cancel may overtake its task. */
    private final Map<String, Boolean> cancelled = bounded();

    public MqttNodeAgent(MqttAgentOptions options, String deviceId, Host host, Consumer<String> log) {
        this(options, deviceId, host, log, PahoMqttLink::new, MonteCarlo::run, Timing.defaults());
    }

    MqttNodeAgent(MqttAgentOptions options, String deviceId, Host host, Consumer<String> log,
                  Function<MqttLink.Settings, MqttLink> linkFactory, MonteCarlo.Workload workload,
                  Timing timing) {
        if (!LinuxHost.DEVICE_ID.matcher(deviceId).matches()) {
            throw new IllegalArgumentException("Device id " + deviceId + " cannot be used as an MQTT user name.");
        }
        this.options = options;
        this.deviceId = deviceId;
        this.host = host;
        this.log = log;
        this.linkFactory = linkFactory;
        this.workload = workload;
        this.timing = timing;
        this.topics = new Topics(options.topicPrefix, deviceId);
        this.heartbeatIntervalSeconds = timing.maxHeartbeatIntervalSeconds;
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    /**
     * Connects to the broker and registers with Phoenix Core, in the
     * background. An unreachable broker and a silent Core are retried for
     * as long as the agent runs.
     *
     * @param onFatal called at most once, after the agent stopped because
     *                retrying cannot help: the broker refused the
     *                credentials, TLS could not verify the broker, or Core
     *                refused the registration
     * @throws IllegalArgumentException if the connection cannot even be set up
     *         (for example an unusable CA file)
     */
    public void start(Consumer<String> onFatal) {
        MqttLink created = linkFactory.apply(new MqttLink.Settings(
                options.brokerUri,
                deviceId,
                deviceId,
                options.password,
                options.caFile,
                timing.keepAliveSeconds,
                new MqttLink.Message(topics.up("status"), OFFLINE, QOS, true)));

        synchronized (lock) {
            if (running) {
                throw new IllegalStateException("already started");
            }
            running = true;
            this.onFatal = onFatal;
            this.link = created;
        }

        log("Connecting to " + options.brokerUri + " as " + deviceId + " ("
                + (options.usesTls() ? "TLS" : "plain TCP, local broker") + ")...");
        if (options.validationHoldSeconds > 0) {
            log("Validation hold: every task waits " + options.validationHoldSeconds
                    + " s after it started. Use only for validation runs.");
        }

        try {
            created.open(new LinkListener());
        } catch (RuntimeException e) {
            synchronized (lock) {
                running = false;
                link = null;
            }
            shutdownThreads();
            throw e;
        }
    }

    /** Says goodbye on {@code up/status} and disconnects. Idempotent. */
    public void stop() {
        stop(true);
        log("Agent stopped.");
    }

    public boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    /** The node id Core assigned, or null before the first registration. */
    public String nodeId() {
        synchronized (lock) {
            return nodeId;
        }
    }

    private void stop(boolean farewell) {
        MqttLink closing;
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
            cancel(retry);
            cancel(heartbeats);
            closing = link;
        }
        shutdownThreads();
        if (closing != null) {
            closing.close(farewell ? new MqttLink.Message(topics.up("status"), OFFLINE, QOS, true) : null);
        }
    }

    private void shutdownThreads() {
        scheduler.shutdownNow();
        worker.shutdownNow();
    }

    /** Stops for good and tells the caller why. */
    private void fatal(String message, boolean farewell) {
        Consumer<String> callback;
        synchronized (lock) {
            if (!running) {
                return;
            }
            callback = onFatal;
            onFatal = null;
        }
        log(message);
        // Closing may block; never on the link's own callback thread.
        Thread closer = new Thread(() -> {
            stop(farewell);
            if (callback != null) {
                callback.accept(message);
            }
        }, "phoenix-mqtt-stop");
        closer.setDaemon(true);
        closer.start();
    }

    // ------------------------------------------------------------------
    // connection events
    // ------------------------------------------------------------------

    private final class LinkListener implements MqttLink.Listener {

        @Override
        public void connected(boolean reconnect) {
            MqttLink current;
            synchronized (lock) {
                if (!running) {
                    return;
                }
                current = link;
            }

            current.subscribe(Arrays.asList(topics.downAll, topics.coreStatus), QOS);
            current.publish(new MqttLink.Message(topics.up("status"), ONLINE, QOS, true));
            log(reconnect ? "Reconnected to the broker." : "Connected to the broker.");

            synchronized (lock) {
                if (!running) {
                    return;
                }
                if (sessionToken != null) {
                    // Keep the token across a reconnect; if Core forgot it,
                    // Core answers the next heartbeat with "reregister".
                } else if (requestId != null && !answered) {
                    sendRegistration();
                } else {
                    register();
                }
            }

            sendHeartbeat();
        }

        @Override
        public void connectionLost(String cause) {
            if (isRunning()) {
                log("Broker connection lost: " + cause + "; reconnecting...");
            }
        }

        @Override
        public void connectFailed(String cause, boolean fatal) {
            if (!isRunning()) {
                return;
            }
            if (fatal) {
                fatal("Could not connect to the broker: " + cause, false);
            } else {
                log("Broker not reachable (" + cause + "); retrying...");
            }
        }

        @Override
        public void messageArrived(String topic, byte[] payload) {
            if (!isRunning()) {
                return;
            }

            String kind;
            if (topic.equals(topics.coreStatus)) {
                kind = "core/status";
            } else if (topic.equals(topics.down("registered"))) {
                kind = "registered";
            } else if (topic.equals(topics.down("task"))) {
                kind = "task";
            } else if (topic.equals(topics.down("cancel"))) {
                kind = "cancel";
            } else if (topic.equals(topics.down("control"))) {
                kind = "control";
            } else {
                return; // a kind this version does not know: ignore it
            }

            if (payload.length > 64 * 1024) {
                log("Ignored an oversized " + kind + " message.");
                return;
            }

            Map<String, Object> body;
            try {
                body = Json.parseObject(new String(payload, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                if (!payloadIsEmpty(payload)) {
                    log("Ignored a malformed " + kind + " message.");
                }
                return;
            }

            switch (kind) {
                case "core/status":
                    onCoreStatus(body);
                    break;
                case "registered":
                    onRegistered(body);
                    break;
                case "task":
                    onTask(body);
                    break;
                case "cancel":
                    onCancel(body);
                    break;
                default:
                    onControl(body);
            }
        }
    }

    /** A cleared retained message arrives with an empty payload. */
    private static boolean payloadIsEmpty(byte[] payload) {
        return payload.length == 0;
    }

    // ------------------------------------------------------------------
    // registration
    // ------------------------------------------------------------------

    /** A new registration request. Must be called with lock held. */
    private void register() {
        requestId = UUID.randomUUID().toString();

        Json.Builder body = Json.object()
                .put("requestId", requestId)
                .put("deviceId", deviceId)
                .put("displayName", options.displayName != null ? options.displayName : host.hostName())
                .put("hardwareClass", "LINUX")
                .put("architecture", host.architecture())
                .put("cpuCores", host.cpuCores())
                .put("memoryMb", host.totalRamMb())
                .put("adapterType", "linux")
                .put("capabilities", options.capabilities)
                .put("protocolFeatures", List.of("attempt-id"))
                .put("protocolVersion", PROTOCOL_VERSION);
        if (options.enrollmentToken != null) {
            body.put("enrollmentToken", options.enrollmentToken);
        }

        requestBody = body.toString();
        answered = false;
        log("Registering with Phoenix Core (request " + shortId(requestId) + ")...");
        sendRegistration();
    }

    /**
     * Sends the current request (again: the same requestId gets the same
     * answer) and schedules a resend. Must be called with lock held.
     */
    private void sendRegistration() {
        if (requestId == null || requestBody == null || link == null) {
            return;
        }
        String id = requestId;
        requestSentAtNs = System.nanoTime();
        link.publish(new MqttLink.Message(topics.up("register"), requestBody, QOS, false));

        cancel(retry);
        retry = schedule(timing.registrationRetryMs, () -> retryRegistration(id));
    }

    private void retryRegistration(String id) {
        synchronized (lock) {
            if (!running || !id.equals(requestId) || answered) {
                return;
            }
        }
        log("No answer from Phoenix Core yet; sending the registration again.");
        synchronized (lock) {
            if (running && id.equals(requestId) && !answered) {
                sendRegistration();
            }
        }
    }

    private void onRegistered(Map<String, Object> body) {
        String answerTo = Json.string(body, "requestId");
        List<Map<String, Object>> deferred;
        String refusal = null;
        String message;

        synchronized (lock) {
            // An answer to an earlier request, or a duplicate of this one.
            if (answerTo == null || !answerTo.equals(requestId) || answered || !running) {
                return;
            }
            answered = true;
            cancel(retry);

            String token = Json.string(body, "sessionToken");
            String node = Json.string(body, "nodeId");

            if (!"ok".equals(Json.string(body, "status"))) {
                refusal = Json.string(body, "message", "no reason given");
                deferred = List.of();
                message = null;
            } else if (token == null || token.isEmpty() || node == null || node.isEmpty()) {
                refusal = "malformed registration answer";
                deferred = List.of();
                message = null;
            } else {
                sessionToken = token;
                nodeId = node;
                coreInstanceId = Json.string(body, "coreInstanceId");
                latencyMs = Math.round((System.nanoTime() - requestSentAtNs) / 100_000.0) / 10.0;
                heartbeatIntervalSeconds = Math.max(1, Math.min(timing.maxHeartbeatIntervalSeconds,
                        Json.number(body, "heartbeatIntervalSeconds", timing.maxHeartbeatIntervalSeconds)));

                cancel(heartbeats);
                heartbeats = scheduleRepeating(heartbeatIntervalSeconds, this::sendHeartbeat);

                deferred = List.copyOf(earlyTasks);
                earlyTasks.clear();
                message = "Registered with Phoenix Core: node " + nodeId + " (device " + deviceId
                        + ", registration round trip " + latencyMs + " ms, heartbeat every "
                        + heartbeatIntervalSeconds + " s).";
            }
        }

        if (refusal != null) {
            fatal("Phoenix Core refused the registration: " + refusal, true);
            return;
        }

        log(message);
        sendHeartbeat();
        for (Map<String, Object> task : deferred) {
            onTask(task);
        }
    }

    private void onCoreStatus(Map<String, Object> body) {
        String state = Json.string(body, "state");
        String instance = Json.string(body, "coreInstanceId");
        String message = null;

        synchronized (lock) {
            if (!running) {
                return;
            }
            if ("online".equals(state) && sessionToken != null && instance != null
                    && !instance.equals(coreInstanceId)) {
                message = "Phoenix Core restarted (instance " + shortId(instance) + "); registering again.";
            } else if ("online".equals(state) && sessionToken == null && requestId != null && !answered) {
                // Core just came (back) online while our request waits.
                sendRegistration();
            } else if ("offline".equals(state) && sessionToken != null) {
                message = "Phoenix Core is offline; waiting for it to return.";
            }
        }

        if (message != null) {
            log(message);
            if (message.startsWith("Phoenix Core restarted")) {
                synchronized (lock) {
                    if (running) {
                        register();
                    }
                }
            }
        }
    }

    private void onControl(Map<String, Object> body) {
        if (!"reregister".equals(Json.string(body, "type"))) {
            return; // unknown control types are ignored
        }
        log("Phoenix Core asked this device to register again ("
                + Json.string(body, "reason", "no reason given") + ").");
        synchronized (lock) {
            if (running) {
                register();
            }
        }
    }

    // ------------------------------------------------------------------
    // heartbeats
    // ------------------------------------------------------------------

    private void sendHeartbeat() {
        MqttLink current;
        String token;
        int activeNow;
        int queuedNow;
        double latency;

        synchronized (lock) {
            current = link;
            token = sessionToken;
            if (!running || current == null || token == null || !current.isConnected()) {
                return;
            }
            activeNow = active;
            queuedNow = queued;
            latency = latencyMs;
        }

        double cpu = host.cpuUsagePercent();
        long freeRam = host.availableRamMb();

        String body = Json.object()
                .put("sessionToken", token)
                .put("deviceId", deviceId)
                .put("cpuUsagePercent", cpu) // -1 when unknown: recorded as unknown, never 0 %
                .put("availableRamMb", freeRam)
                .put("activeTasks", activeNow)
                .put("queueLength", queuedNow)
                .put("networkLatencyMs", latency)
                .put("energyCostEstimate", ENERGY_COST_ESTIMATE)
                .toString();

        if (current.publish(new MqttLink.Message(topics.up("heartbeat"), body, 0, false))) {
            log(String.format(java.util.Locale.ROOT,
                    "Heartbeat sent: cpu=%s freeRam=%dMB activeTasks=%d queued=%d rtt=%.1fms energy=%.1f",
                    cpu < 0 ? "unknown" : String.format(java.util.Locale.ROOT, "%.1f%%", cpu),
                    freeRam, activeNow, queuedNow, latency, ENERGY_COST_ESTIMATE));
        } else {
            log("Heartbeat failed: not connected to the broker.");
        }
    }

    // ------------------------------------------------------------------
    // tasks
    // ------------------------------------------------------------------

    private enum Decision { RUN, DUPLICATE_RUNNING, DUPLICATE_DONE, CANCELLED, DEFERRED, DROPPED }

    private void onTask(Map<String, Object> task) {
        String taskId = Json.string(task, "taskId");
        String attemptId = Json.string(task, "attemptId");

        if (taskId == null || taskId.isEmpty() || attemptId == null || attemptId.isEmpty()) {
            log("Ignored a task without taskId or attemptId.");
            return;
        }

        Map<String, Object> known = null;
        Decision decision;

        synchronized (lock) {
            if (!running) {
                return;
            }

            if (sessionToken == null) {
                // A task the broker kept for us while we were away can arrive
                // before the registration answer; acknowledge it with the new
                // token once we have one.
                earlyTasks.addLast(task);
                if (earlyTasks.size() > MAX_EARLY_TASKS) {
                    earlyTasks.removeFirst();
                    decision = Decision.DROPPED;
                } else {
                    decision = Decision.DEFERRED;
                }
            } else {
                // 1. Acknowledge every delivery, duplicates included.
                report("ack", taskId, attemptId);

                if (handled.containsKey(attemptId)) {
                    known = handled.get(attemptId);
                    decision = known == null ? Decision.DUPLICATE_RUNNING : Decision.DUPLICATE_DONE;
                } else if (cancelled.containsKey(attemptId)) {
                    decision = Decision.CANCELLED;
                } else {
                    handled.put(attemptId, null);
                    queued++;
                    decision = Decision.RUN;
                }
            }
        }

        String attempt = "attempt id " + shortId(attemptId);

        switch (decision) {
            case RUN:
                try {
                    worker.execute(() -> runTask(task, taskId, attemptId));
                } catch (RejectedExecutionException e) {
                    // The agent stopped meanwhile.
                }
                break;
            case DUPLICATE_RUNNING:
                log("Duplicate delivery of task " + taskId + " (" + attempt + "): already running, not executed twice.");
                break;
            case DUPLICATE_DONE:
                // 2. Never run an attempt twice; re-send the known result.
                resendResult(known);
                log("Duplicate delivery of task " + taskId + " (" + attempt + "): result sent again, not executed twice.");
                break;
            case CANCELLED:
                log("Task " + taskId + " (" + attempt + ") was cancelled by Phoenix Core before it arrived; skipped.");
                break;
            case DEFERRED:
                log("Task " + taskId + " (" + attempt + ") arrived before the registration answer; it waits for it.");
                break;
            default:
                log("Too many tasks before the registration answer; the oldest was dropped (Phoenix Core will place it again).");
        }
    }

    private void onCancel(Map<String, Object> body) {
        String attemptId = Json.string(body, "attemptId");
        if (attemptId == null) {
            return;
        }
        String taskId = Json.string(body, "taskId", "?");
        String reason = Json.string(body, "reason", "no reason given");

        synchronized (lock) {
            cancelled.put(attemptId, Boolean.TRUE);
        }
        log("Phoenix Core cancelled task " + taskId + " (attempt id " + shortId(attemptId) + ", " + reason + ").");
    }

    private void runTask(Map<String, Object> task, String taskId, String attemptId) {
        boolean skip;
        synchronized (lock) {
            if (!running) {
                return;
            }
            queued--;
            if (cancelled.containsKey(attemptId)) {
                handled.remove(attemptId);
                skip = true;
            } else {
                active++;
                skip = false;
            }
        }

        if (skip) {
            log("Task " + taskId + " was cancelled by Phoenix Core before it started; skipped.");
            return;
        }

        try {
            execute(task, taskId, attemptId);
        } finally {
            synchronized (lock) {
                active--;
            }
        }
    }

    private void execute(Map<String, Object> task, String taskId, String attemptId) {
        String type = Json.string(task, "type", TASK_TYPE);
        long samples = Json.number(task, "samples", DEFAULT_SAMPLES);
        long attemptNumber = Json.number(task, "attemptNumber", -1);

        log(String.format(java.util.Locale.ROOT,
                "Received task %s (attempt %d, id %s), executing Monte-Carlo (samples=%d)...",
                taskId, attemptNumber, shortId(attemptId), samples));

        synchronized (lock) {
            if (!running) {
                return;
            }
            report("started", taskId, attemptId);
        }

        BooleanSupplier stop = () -> {
            synchronized (lock) {
                return !running || cancelled.containsKey(attemptId);
            }
        };

        if (!hold(taskId, stop)) {
            discard(taskId, attemptId);
            return;
        }

        long started = System.nanoTime();
        String status = "SUCCESS";
        String output;
        long executionTimeMs;

        try {
            if (!TASK_TYPE.equals(type)) {
                status = "FAILED";
                output = "Unsupported task type: " + type;
                executionTimeMs = 0;
            } else if (samples <= 0 || samples > MAX_SAMPLES) {
                status = "FAILED";
                output = "Invalid sample count: " + samples;
                executionTimeMs = 0;
            } else {
                MonteCarlo.Result result = workload.run(samples, stop);
                output = result.output;
                executionTimeMs = result.executionTimeMs;
            }
        } catch (CancellationException e) {
            discard(taskId, attemptId);
            return;
        } catch (RuntimeException e) {
            status = "FAILED";
            output = "Execution failed: " + e.getMessage();
            executionTimeMs = (System.nanoTime() - started) / 1_000_000;
        }

        if (stop.getAsBoolean()) {
            discard(taskId, attemptId);
            return;
        }

        boolean published;
        synchronized (lock) {
            if (!running) {
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sessionToken", sessionToken);
            body.put("taskId", taskId);
            body.put("attemptId", attemptId);
            body.put("status", status);
            body.put("outputData", output);
            body.put("executionTimeMs", executionTimeMs);
            handled.put(attemptId, body);
            published = link != null && link.publish(new MqttLink.Message(topics.up("result"), Json.write(body), QOS, false));
        }

        String delivery = published ? "" : " (result queued until the broker is reachable)";
        log("SUCCESS".equals(status)
                ? "Task " + taskId + " done: " + output + " (" + executionTimeMs + "ms)" + delivery
                : "Task " + taskId + " failed: " + output + delivery);
    }

    /**
     * The validation hold (normally 0 s): waits, checking {@code stop} every
     * 100 ms. False if the attempt was stopped meanwhile.
     */
    private boolean hold(String taskId, BooleanSupplier stop) {
        int seconds = options.validationHoldSeconds;
        if (seconds <= 0) {
            return true;
        }
        log("Holding task " + taskId + " for " + seconds + " s before executing (validation hold)...");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        try {
            while (System.nanoTime() < until) {
                if (stop.getAsBoolean()) {
                    return false;
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !stop.getAsBoolean();
    }

    /** A cancelled or interrupted attempt: no result is sent; Core re-places the task if needed. */
    private void discard(String taskId, String attemptId) {
        boolean cancelledByCore;
        boolean stillRunning;
        synchronized (lock) {
            cancelledByCore = cancelled.containsKey(attemptId);
            stillRunning = running;
            if (handled.containsKey(attemptId) && handled.get(attemptId) == null) {
                handled.remove(attemptId);
            }
        }
        if (cancelledByCore) {
            log("Task " + taskId + " stopped: cancelled by Phoenix Core; no result sent.");
        } else if (!stillRunning) {
            log("Task " + taskId + " interrupted: the agent stopped; Phoenix Core will place it again.");
        }
    }

    private void resendResult(Map<String, Object> known) {
        String body;
        MqttLink current;
        synchronized (lock) {
            // The current token: an earlier one stops counting after a re-registration.
            Map<String, Object> copy = new LinkedHashMap<>(known);
            copy.put("sessionToken", sessionToken);
            body = Json.write(copy);
            current = link;
        }
        if (current != null) {
            current.publish(new MqttLink.Message(topics.up("result"), body, QOS, false));
        }
    }

    /** Publishes {sessionToken, taskId, attemptId} on up/kind. Must be called with lock held. */
    private void report(String kind, String taskId, String attemptId) {
        if (link == null) {
            return;
        }
        String body = Json.object()
                .put("sessionToken", sessionToken)
                .put("taskId", taskId)
                .put("attemptId", attemptId)
                .toString();
        link.publish(new MqttLink.Message(topics.up(kind), body, QOS, false));
    }

    // ------------------------------------------------------------------
    // timers and helpers
    // ------------------------------------------------------------------

    private ScheduledFuture<?> schedule(long delayMs, Runnable action) {
        try {
            return scheduler.schedule(() -> guarded(action), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    private ScheduledFuture<?> scheduleRepeating(long periodSeconds, Runnable action) {
        try {
            return scheduler.scheduleWithFixedDelay(() -> guarded(action), periodSeconds, periodSeconds,
                    TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    /** A timer task that throws would silently cancel its schedule. */
    private void guarded(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log("Agent error: " + e.getMessage());
        }
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private void log(String message) {
        log.accept(message);
    }

    static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static <V> Map<String, V> bounded() {
        return new LinkedHashMap<String, V>() {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > REMEMBERED_ATTEMPTS;
            }
        };
    }
}

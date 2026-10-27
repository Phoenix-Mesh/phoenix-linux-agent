package com.phoenix.linuxagent.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The MQTT agent against a scripted broker connection. */
class MqttNodeAgentTest {

    private static final String DEVICE = "linux-test";
    private static final String DOWN = "phoenix/v1/nodes/" + DEVICE + "/down/";
    private static final String UP = "phoenix/v1/nodes/" + DEVICE + "/up/";
    private static final String CORE_STATUS = "phoenix/v1/core/status";

    /** A host with fixed values. */
    static final class FakeHost implements MqttNodeAgent.Host {

        volatile double cpu = 12.5;

        @Override
        public String hostName() {
            return "test-host";
        }

        @Override
        public String architecture() {
            return "amd64";
        }

        @Override
        public int cpuCores() {
            return 4;
        }

        @Override
        public long totalRamMb() {
            return 8192;
        }

        @Override
        public long availableRamMb() {
            return 2048;
        }

        @Override
        public double cpuUsagePercent() {
            return cpu;
        }
    }

    /** A broker connection the test drives by hand. */
    static final class FakeLink implements MqttLink {

        volatile MqttLink.Settings settings;
        volatile Listener listener;
        final List<Message> published = new CopyOnWriteArrayList<>();
        final List<String> subscriptions = new CopyOnWriteArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile Message farewell;
        volatile boolean up;

        @Override
        public boolean isConnected() {
            return up;
        }

        @Override
        public void open(Listener listener) {
            this.listener = listener;
        }

        @Override
        public boolean subscribe(List<String> filters, int qos) {
            subscriptions.addAll(filters);
            return true;
        }

        @Override
        public boolean publish(Message message) {
            published.add(message);
            return true;
        }

        @Override
        public void close(Message farewell) {
            this.farewell = farewell;
            up = false;
            closed.countDown();
        }

        void connect(boolean reconnect) {
            up = true;
            listener.connected(reconnect);
        }

        void drop() {
            up = false;
            listener.connectionLost("network gone");
        }

        void deliver(String kind, String body) {
            listener.messageArrived(DOWN + kind, body.getBytes(StandardCharsets.UTF_8));
        }

        void coreStatus(String body) {
            listener.messageArrived(CORE_STATUS, body.getBytes(StandardCharsets.UTF_8));
        }

        List<Message> sent(String kind) {
            return published.stream().filter(m -> m.topic.equals(UP + kind)).collect(Collectors.toList());
        }

        List<Map<String, Object>> bodies(String kind) {
            return sent(kind).stream().map(m -> Json.parseObject(m.payload)).collect(Collectors.toList());
        }

        String lastRequestId() {
            List<Map<String, Object>> requests = bodies("register");
            return Json.string(requests.get(requests.size() - 1), "requestId");
        }

        void answer(String token, String instance, String requestId) {
            deliver("registered", "{\"requestId\":\"" + requestId + "\",\"status\":\"ok\",\"nodeId\":\"node-1\","
                    + "\"sessionToken\":\"" + token + "\",\"heartbeatIntervalSeconds\":5,"
                    + "\"coreInstanceId\":\"" + instance + "\",\"protocolVersion\":1}");
        }

        void answer(String token) {
            answer(token, "core-1", lastRequestId());
        }

        void answer() {
            answer("tok-1");
        }
    }

    /** A workload the test controls. */
    interface TestWorkload {
        MonteCarlo.Result run(long samples, BooleanSupplier stop) throws InterruptedException;
    }

    private final FakeLink link = new FakeLink();
    private final FakeHost host = new FakeHost();
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final List<String> fatal = new CopyOnWriteArrayList<>();
    private final List<Long> runs = new CopyOnWriteArrayList<>();

    /** Default workload: instant and counted. */
    private volatile TestWorkload workload = (samples, stop) -> {
        runs.add(samples);
        return new MonteCarlo.Result("Computed Monte-Carlo pi ~= 3.14", 7);
    };

    private MqttNodeAgent agent;

    @AfterEach
    void stopAgent() {
        if (agent != null) {
            agent.stop();
        }
    }

    private MqttNodeAgent agent(String enrollmentToken, int holdSeconds) {
        MqttAgentOptions.Builder options = new MqttAgentOptions.Builder()
                .broker("broker.test")
                .password("pw")
                .capability("gpu-less")
                .validationHoldSeconds(holdSeconds);
        if (enrollmentToken != null) {
            options.enrollmentToken(enrollmentToken);
        }
        return new MqttNodeAgent(options.build(), DEVICE, host, logs::add,
                settings -> {
                    link.settings = settings;
                    return link;
                },
                (samples, stop) -> {
                    try {
                        return workload.run(samples, stop);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("interrupted");
                    }
                },
                new MqttNodeAgent.Timing(150, 5, 30));
    }

    private void start(String enrollmentToken, int holdSeconds) {
        agent = agent(enrollmentToken, holdSeconds);
        agent.start(fatal::add);
    }

    private void start() {
        start("enroll-123456789", 0);
    }

    /** Connected and registered as node-1 with session token tok-1. */
    private void registered(int holdSeconds) {
        start("enroll-123456789", holdSeconds);
        link.connect(false);
        link.answer();
        assertEquals("node-1", agent.nodeId());
    }

    private void registered() {
        registered(0);
    }

    private static boolean eventually(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private static void pause(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private static String task(String attemptId, String taskId, long samples, String type) {
        return "{\"taskId\":\"" + taskId + "\",\"type\":\"" + type + "\",\"samples\":" + samples
                + ",\"attemptId\":\"" + attemptId + "\",\"attemptNumber\":1}";
    }

    private static String task(String attemptId) {
        return task(attemptId, "task-001", 1_000, "Monte-Carlo-Pi");
    }

    private boolean logged(String prefix) {
        return logs.stream().anyMatch(line -> line.startsWith(prefix));
    }

    private boolean loggedContaining(String text) {
        return logs.stream().anyMatch(line -> line.contains(text));
    }

    // ------------------------------------------------------------------
    // connection and registration
    // ------------------------------------------------------------------

    @Test
    void theDeviceIdIsTheBrokerUserAndTheWillSaysOffline() {
        start();

        assertEquals("ssl://broker.test:8883", link.settings.brokerUri);
        assertEquals(DEVICE, link.settings.username);
        assertEquals(DEVICE, link.settings.clientId);
        assertEquals(UP + "status", link.settings.will.topic);
        assertEquals("offline", Json.string(Json.parseObject(link.settings.will.payload), "state"));
        assertTrue(link.settings.will.retained);
        assertEquals(1, link.settings.will.qos);
    }

    @Test
    void onConnectingItSubscribesAnnouncesItselfAndRegisters() {
        start();
        link.connect(false);

        assertEquals(List.of(DOWN + "#", CORE_STATUS), link.subscriptions);

        MqttLink.Message status = link.sent("status").get(0);
        assertTrue(status.retained);
        assertEquals("online", Json.string(Json.parseObject(status.payload), "state"));

        List<Map<String, Object>> requests = link.bodies("register");
        assertEquals(1, requests.size());
        Map<String, Object> register = requests.get(0);
        assertEquals(DEVICE, register.get("deviceId"));
        assertEquals("test-host", register.get("displayName"));
        assertEquals("LINUX", register.get("hardwareClass"));
        assertEquals("linux", register.get("adapterType"));
        assertEquals("amd64", register.get("architecture"));
        assertEquals(4L, register.get("cpuCores"));
        assertEquals(8192L, register.get("memoryMb"));
        assertEquals(List.of("attempt-id"), register.get("protocolFeatures"));
        assertEquals(List.of("gpu-less"), register.get("capabilities"));
        assertEquals(1L, register.get("protocolVersion"));
        assertEquals("enroll-123456789", register.get("enrollmentToken"));
        assertFalse(Json.string(register, "requestId").isBlank());
    }

    @Test
    void withoutAnEnrollmentTokenNoneIsSent() {
        start(null, 0);
        link.connect(false);

        assertFalse(link.bodies("register").get(0).containsKey("enrollmentToken"));
    }

    @Test
    void anAcceptedRegistrationStartsHeartbeatsWithRealTelemetry() {
        start();
        link.connect(false);

        link.answer("tok-1", "core-1", "someone-elses-request");
        assertNull(agent.nodeId());

        link.answer();
        link.answer(); // duplicate answer

        assertEquals("node-1", agent.nodeId());
        assertEquals(1, logs.stream().filter(l -> l.startsWith("Registered with Phoenix Core: node node-1")).count());

        MqttLink.Message heartbeat = link.sent("heartbeat").get(0);
        assertEquals(0, heartbeat.qos);
        assertFalse(heartbeat.retained);
        Map<String, Object> body = Json.parseObject(heartbeat.payload);
        assertEquals("tok-1", body.get("sessionToken"));
        assertEquals(12.5, body.get("cpuUsagePercent"));
        assertEquals(2048L, body.get("availableRamMb"));
        assertEquals(0L, body.get("activeTasks"));
        assertEquals(0L, body.get("queueLength"));
        assertEquals(4.5, body.get("energyCostEstimate"));
        assertTrue(body.get("networkLatencyMs") instanceof Double);
        assertTrue(logged("Heartbeat sent: cpu=12.5%"));
    }

    @Test
    void anUnknownCpuUsageIsSentAsMinusOneNeverAsZero() {
        host.cpu = -1.0;
        registered();

        Map<String, Object> body = link.bodies("heartbeat").get(0);
        assertEquals(-1.0, body.get("cpuUsagePercent"));
        assertTrue(logged("Heartbeat sent: cpu=unknown"));
    }

    @Test
    void heartbeatsFollowTheIntervalCoreAsksForUpToFiveSeconds() throws InterruptedException {
        start();
        link.connect(false);
        link.deliver("registered", "{\"requestId\":\"" + link.lastRequestId() + "\",\"status\":\"ok\","
                + "\"nodeId\":\"node-1\",\"sessionToken\":\"tok-1\",\"heartbeatIntervalSeconds\":1,"
                + "\"coreInstanceId\":\"core-1\"}");

        int first = link.sent("heartbeat").size();
        pause(2_300);
        assertTrue(link.sent("heartbeat").size() >= first + 2);
    }

    @Test
    void aRefusedRegistrationStopsTheAgentAndSaysWhy() throws InterruptedException {
        start();
        link.connect(false);
        link.deliver("registered",
                "{\"requestId\":\"" + link.lastRequestId() + "\",\"status\":\"error\",\"message\":\"enrollment_rejected\"}");

        assertTrue(eventually(() -> !fatal.isEmpty()));
        assertTrue(fatal.get(0).contains("enrollment_rejected"));
        assertFalse(agent.isRunning());
        assertTrue(link.closed.await(5, TimeUnit.SECONDS));
        assertNotNull(link.farewell);
        assertNull(agent.nodeId());
    }

    @Test
    void anUnansweredRegistrationIsSentAgainWithTheSameRequestId() throws InterruptedException {
        start();
        link.connect(false);

        assertTrue(eventually(() -> link.sent("register").size() >= 2));
        assertTrue(logged("No answer from Phoenix Core yet"));
        assertEquals(1, link.bodies("register").stream().map(b -> b.get("requestId")).distinct().count());

        link.answer();
        int after = link.sent("register").size();
        pause(400);
        assertEquals(after, link.sent("register").size());
    }

    @Test
    void anUnreachableBrokerIsRetriedButRefusedCredentialsAreFatal() {
        start();

        link.listener.connectFailed("connection refused", false);
        assertTrue(agent.isRunning());
        assertTrue(logged("Broker not reachable (connection refused); retrying"));

        link.listener.connectFailed("the broker refused this device's user name or password", true);
        assertTrue(eventually(() -> !fatal.isEmpty()));
        assertFalse(agent.isRunning());
        assertTrue(fatal.get(0).contains("user name or password"));
        assertNull(link.farewell);
    }

    @Test
    void aReconnectKeepsTheSessionAndHeartbeatsAtOnce() {
        registered();
        int heartbeats = link.sent("heartbeat").size();

        link.drop();
        assertTrue(logged("Broker connection lost: network gone"));

        link.connect(true);

        assertEquals(1, link.sent("register").size());
        assertTrue(link.sent("heartbeat").size() > heartbeats);
        assertTrue(logs.contains("Reconnected to the broker."));
        assertEquals(2, link.sent("status").size());
    }

    @Test
    void heartbeatsAreNotSentWhileDisconnected() throws InterruptedException {
        registered();
        link.drop();
        int heartbeats = link.sent("heartbeat").size();

        pause(1_200);

        assertEquals(heartbeats, link.sent("heartbeat").size());
    }

    @Test
    void aReregisterRequestOrANewCoreInstanceRegistersAgain() {
        registered();

        link.coreStatus("{\"state\":\"online\",\"coreInstanceId\":\"core-1\"}");
        assertEquals(1, link.sent("register").size());

        link.deliver("control", "{\"type\":\"reregister\",\"reason\":\"invalid_session\"}");
        assertEquals(2, link.sent("register").size());
        assertTrue(logged("Phoenix Core asked this device to register again (invalid_session)"));
        link.answer("tok-2");

        link.coreStatus("{\"state\":\"online\",\"coreInstanceId\":\"core-2\"}");
        assertEquals(3, link.sent("register").size());
        assertTrue(logged("Phoenix Core restarted"));

        assertEquals(3, new HashSet<>(link.bodies("register").stream()
                .map(b -> b.get("requestId")).collect(Collectors.toList())).size());

        link.deliver("control", "{\"type\":\"something-new\"}");
        assertEquals(3, link.sent("register").size());
    }

    @Test
    void coreGoingOfflineIsOnlyLogged() {
        registered();

        link.coreStatus("{\"state\":\"offline\",\"coreInstanceId\":\"core-1\"}");

        assertTrue(logged("Phoenix Core is offline; waiting for it to return."));
        assertTrue(agent.isRunning());
        assertEquals(1, link.sent("register").size());
    }

    @Test
    void coreComingOnlineResendsAPendingRegistration() {
        start();
        link.connect(false);
        String requestId = link.lastRequestId();

        link.coreStatus("{\"state\":\"online\",\"coreInstanceId\":\"core-9\"}");

        assertEquals(List.of(requestId, requestId),
                link.bodies("register").stream().map(b -> b.get("requestId")).collect(Collectors.toList()));
    }

    @Test
    void anEmptyRetainedMessageIsIgnoredQuietly() {
        registered();

        link.listener.messageArrived(CORE_STATUS, new byte[0]);

        assertFalse(loggedContaining("malformed"));
    }

    // ------------------------------------------------------------------
    // tasks
    // ------------------------------------------------------------------

    @Test
    void aTaskIsAcknowledgedRunAndReportedWithItsAttemptId() {
        registered();

        link.deliver("task", task("attempt-1"));

        assertTrue(eventually(() -> !link.sent("result").isEmpty()));

        Map<String, Object> ack = link.bodies("ack").get(0);
        Map<String, Object> started = link.bodies("started").get(0);
        Map<String, Object> result = link.bodies("result").get(0);

        for (Map<String, Object> report : List.of(ack, started, result)) {
            assertEquals("tok-1", report.get("sessionToken"));
            assertEquals("task-001", report.get("taskId"));
            assertEquals("attempt-1", report.get("attemptId"));
        }
        assertEquals("SUCCESS", result.get("status"));
        assertEquals("Computed Monte-Carlo pi ~= 3.14", result.get("outputData"));
        assertEquals(7L, result.get("executionTimeMs"));
        assertEquals(List.of(1_000L), runs);

        List<String> order = link.published.stream()
                .map(m -> m.topic.substring(m.topic.lastIndexOf('/') + 1))
                .filter(kind -> kind.equals("ack") || kind.equals("started") || kind.equals("result"))
                .collect(Collectors.toList());
        assertEquals(List.of("ack", "started", "result"), order);
        for (MqttLink.Message m : link.published) {
            if (m.topic.endsWith("/ack") || m.topic.endsWith("/started") || m.topic.endsWith("/result")) {
                assertEquals(1, m.qos);
                assertFalse(m.retained);
            }
        }
        assertTrue(logged("Received task task-001 (attempt 1, id attempt-),"));
        assertTrue(logged("Task task-001 done: Computed Monte-Carlo pi ~= 3.14 (7ms)"));
    }

    @Test
    void anAttemptIdWithEscapesIsEchoedVerbatim() {
        registered();
        String odd = "a\\\"b\\\\c\\u00e9";

        link.deliver("task", "{\"taskId\":\"task-001\",\"type\":\"Monte-Carlo-Pi\",\"samples\":10,"
                + "\"attemptId\":\"" + odd + "\",\"attemptNumber\":1}");

        assertTrue(eventually(() -> !link.sent("result").isEmpty()));
        assertEquals("a\"b\\cé", link.bodies("result").get(0).get("attemptId"));
        assertEquals("a\"b\\cé", link.bodies("ack").get(0).get("attemptId"));
    }

    @Test
    void aRedeliveredAttemptIsAcknowledgedAgainButRunOnlyOnce() {
        registered();

        link.deliver("task", task("attempt-1"));
        assertTrue(eventually(() -> !link.sent("result").isEmpty()));

        link.deliver("task", task("attempt-1"));

        assertEquals(2, link.sent("ack").size());
        assertEquals(1, runs.size());
        // The known result is sent again, for Core to ignore if it has it.
        assertEquals(2, link.sent("result").size());
        assertEquals(link.bodies("result").get(0), link.bodies("result").get(1));
        assertTrue(loggedContaining("result sent again, not executed twice"));
    }

    @Test
    void aDuplicateWhileTheAttemptRunsIsOnlyAcknowledged() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        workload = (samples, stop) -> {
            runs.add(samples);
            release.await(5, TimeUnit.SECONDS);
            return new MonteCarlo.Result("done", 1);
        };
        registered();

        link.deliver("task", task("attempt-1"));
        assertTrue(eventually(() -> runs.size() == 1));
        link.deliver("task", task("attempt-1"));
        release.countDown();

        assertTrue(eventually(() -> !link.sent("result").isEmpty()));
        pause(100);
        assertEquals(2, link.sent("ack").size());
        assertEquals(1, runs.size());
        assertEquals(1, link.sent("result").size());
        assertTrue(loggedContaining("already running, not executed twice"));
    }

    @Test
    void aRetryOfTheSameTaskIsANewAttemptAndRuns() {
        registered();

        link.deliver("task", task("attempt-1"));
        assertTrue(eventually(() -> link.sent("result").size() == 1));
        link.deliver("task", task("attempt-2"));
        assertTrue(eventually(() -> link.sent("result").size() == 2));

        assertEquals(2, runs.size());
        assertEquals(List.of("attempt-1", "attempt-2"),
                link.bodies("result").stream().map(b -> b.get("attemptId")).collect(Collectors.toList()));
    }

    @Test
    void activeTasksAndQueueLengthAreReported() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        workload = (samples, stop) -> {
            runs.add(samples);
            release.await(5, TimeUnit.SECONDS);
            return new MonteCarlo.Result("done", 1);
        };
        start();
        link.connect(false);
        link.deliver("registered", "{\"requestId\":\"" + link.lastRequestId() + "\",\"status\":\"ok\","
                + "\"nodeId\":\"node-1\",\"sessionToken\":\"tok-1\",\"heartbeatIntervalSeconds\":1}");

        link.deliver("task", task("attempt-1"));
        link.deliver("task", task("attempt-2", "task-002", 1_000, "Monte-Carlo-Pi"));
        assertTrue(eventually(() -> runs.size() == 1));

        assertTrue(eventually(() -> link.bodies("heartbeat").stream()
                .anyMatch(b -> Long.valueOf(1).equals(b.get("activeTasks")) && Long.valueOf(1).equals(b.get("queueLength")))));
        release.countDown();
        assertTrue(eventually(() -> link.sent("result").size() == 2));
    }

    @Test
    void aCancelThatOvertakesItsTaskMeansTheTaskIsNotRun() throws InterruptedException {
        registered();

        link.deliver("cancel", "{\"taskId\":\"task-001\",\"attemptId\":\"attempt-1\",\"reason\":\"delivery_timeout\"}");
        link.deliver("task", task("attempt-1"));
        pause(200);

        assertEquals(1, link.sent("ack").size());
        assertTrue(runs.isEmpty());
        assertTrue(link.sent("started").isEmpty());
        assertTrue(link.sent("result").isEmpty());
        assertTrue(logged("Phoenix Core cancelled task task-001 (attempt id attempt-, delivery_timeout)"));
    }

    @Test
    void cancellingARunningAttemptStopsItWithoutAResult() {
        workload = (samples, stop) -> {
            runs.add(samples);
            while (!stop.getAsBoolean()) {
                Thread.sleep(5);
            }
            throw new CancellationException("stopped");
        };
        registered();

        link.deliver("task", task("attempt-1"));
        assertTrue(eventually(() -> runs.size() == 1));
        link.deliver("cancel", "{\"taskId\":\"task-001\",\"attemptId\":\"attempt-1\",\"reason\":\"execution_timeout\"}");

        assertTrue(eventually(() -> loggedContaining("cancelled by Phoenix Core; no result sent")));
        assertTrue(link.sent("result").isEmpty());
    }

    @Test
    void theValidationHoldDelaysExecutionAndACancelEndsIt() {
        registered(30);

        link.deliver("task", task("attempt-1"));
        assertTrue(eventually(() -> logged("Holding task task-001 for 30 s")));
        assertEquals(1, link.sent("started").size());
        assertTrue(runs.isEmpty());

        link.deliver("cancel", "{\"taskId\":\"task-001\",\"attemptId\":\"attempt-1\",\"reason\":\"execution_timeout\"}");

        assertTrue(eventually(() -> loggedContaining("cancelled by Phoenix Core; no result sent")));
        assertTrue(runs.isEmpty());
        assertTrue(link.sent("result").isEmpty());
    }

    @Test
    void stoppingDuringTheValidationHoldSendsNoResult() throws InterruptedException {
        registered(30);

        link.deliver("task", task("attempt-1"));
        assertTrue(eventually(() -> logged("Holding task task-001")));
        agent.stop();
        pause(300);

        assertTrue(runs.isEmpty());
        assertTrue(link.sent("result").isEmpty());
    }

    @Test
    void unsupportedWorkReportsAFailedResult() {
        registered();

        link.deliver("task", task("attempt-1", "task-001", 1_000, "Matrix-Multiply"));
        link.deliver("task", task("attempt-2", "task-002", 0, "Monte-Carlo-Pi"));

        assertTrue(eventually(() -> link.sent("result").size() == 2));
        List<Map<String, Object>> results = link.bodies("result");
        assertEquals(List.of("FAILED", "FAILED"),
                results.stream().map(b -> b.get("status")).collect(Collectors.toList()));
        assertTrue(Json.string(results.get(0), "outputData").contains("Unsupported task type"));
        assertTrue(Json.string(results.get(1), "outputData").contains("Invalid sample count"));
        assertTrue(runs.isEmpty());
        assertTrue(logged("Task task-001 failed:"));
    }

    @Test
    void aWorkloadErrorIsReportedAsFailedNotSwallowed() {
        workload = (samples, stop) -> {
            throw new IllegalStateException("out of luck");
        };
        registered();

        link.deliver("task", task("attempt-1"));

        assertTrue(eventually(() -> !link.sent("result").isEmpty()));
        Map<String, Object> result = link.bodies("result").get(0);
        assertEquals("FAILED", result.get("status"));
        assertEquals("Execution failed: out of luck", result.get("outputData"));
    }

    @Test
    void aResultAfterReRegisteringCarriesTheNewToken() {
        CountDownLatch release = new CountDownLatch(1);
        workload = (samples, stop) -> {
            runs.add(samples);
            release.await(5, TimeUnit.SECONDS);
            return new MonteCarlo.Result("done", 1);
        };
        registered();

        link.deliver("task", task("attempt-1"));
        assertTrue(eventually(() -> runs.size() == 1));

        link.deliver("control", "{\"type\":\"reregister\",\"reason\":\"invalid_session\"}");
        link.answer("tok-2");
        release.countDown();

        assertTrue(eventually(() -> !link.sent("result").isEmpty()));
        assertEquals("tok-2", link.bodies("result").get(0).get("sessionToken"));
    }

    @Test
    void aTaskBeforeTheRegistrationAnswerWaitsAndRunsWithTheNewToken() throws InterruptedException {
        start();
        link.connect(false);

        link.deliver("task", task("attempt-1"));
        pause(150);
        assertTrue(link.sent("ack").isEmpty());
        assertTrue(runs.isEmpty());
        assertTrue(logged("Task task-001 (attempt id attempt-) arrived before the registration answer"));

        link.answer();

        assertTrue(eventually(() -> !link.sent("result").isEmpty()));
        assertEquals("tok-1", link.bodies("ack").get(0).get("sessionToken"));
        assertEquals("tok-1", link.bodies("result").get(0).get("sessionToken"));
        assertEquals(1, runs.size());
    }

    @Test
    void malformedOrIncompleteMessagesAreIgnored() {
        registered();

        link.deliver("task", "not json");
        link.deliver("task", "{\"taskId\":\"task-001\"}");
        link.deliver("registered", "[]");
        link.listener.messageArrived(DOWN + "unknown-kind", "{}".getBytes(StandardCharsets.UTF_8));

        assertTrue(link.sent("ack").isEmpty());
        assertTrue(logs.contains("Ignored a malformed task message."));
        assertTrue(logs.contains("Ignored a task without taskId or attemptId."));

        // Still working.
        link.deliver("task", task("attempt-1"));
        assertTrue(eventually(() -> !link.sent("result").isEmpty()));
    }

    // ------------------------------------------------------------------
    // stopping
    // ------------------------------------------------------------------

    @Test
    void stoppingSaysOfflineAndIgnoresLaterMessages() throws InterruptedException {
        registered();

        agent.stop();

        assertTrue(link.closed.await(5, TimeUnit.SECONDS));
        MqttLink.Message farewell = link.farewell;
        assertNotNull(farewell);
        assertEquals(UP + "status", farewell.topic);
        assertEquals("offline", Json.string(Json.parseObject(farewell.payload), "state"));
        assertTrue(farewell.retained);
        assertFalse(agent.isRunning());
        assertEquals("Agent stopped.", logs.get(logs.size() - 1));

        link.deliver("task", task("attempt-1"));
        assertTrue(link.sent("ack").isEmpty());
    }

    @Test
    void anUnusableDeviceIdIsRefused() {
        MqttAgentOptions options = new MqttAgentOptions.Builder().broker("broker.test").password("pw").build();
        try {
            new MqttNodeAgent(options, "bad id/with+wildcards", host, logs::add);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }
}

// SPDX-License-Identifier: Apache-2.0

package com.phoenix.linuxagent.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent's real MQTT stack (Paho, TLS, host name verification) against a
 * real Mosquitto configured like Phoenix Core's deploy/mosquitto, with a
 * scripted Core on the loopback listener.
 */
class MosquittoIntegrationTest {

    private static final String DEVICE = "linux-it-0001";

    @TempDir
    static Path dir;

    private static MosquittoBroker broker;

    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final List<String> fatal = new CopyOnWriteArrayList<>();
    private MqttNodeAgent agent;
    private MqttClient core;

    @BeforeAll
    static void startBroker() throws Exception {
        MosquittoBroker.assumeInstalled();
        broker = MosquittoBroker.start(dir, List.of(DEVICE, "linux-other"));
    }

    @AfterAll
    static void stopBroker() throws InterruptedException {
        if (broker != null) {
            broker.close();
        }
    }

    @AfterEach
    void stop() throws Exception {
        if (agent != null) {
            agent.stop();
        }
        if (core != null && core.isConnected()) {
            core.disconnect();
            core.close();
        }
    }

    private MqttNodeAgent agent(String host, String password, Path ca) {
        MqttAgentOptions options = new MqttAgentOptions.Builder()
                .broker("ssl://" + host + ":" + broker.tlsPort())
                .password(password)
                .caFile(ca)
                .build();
        return new MqttNodeAgent(options, DEVICE, new LinuxHost(), logs::add,
                PahoMqttLink::new, MonteCarlo::run, new MqttNodeAgent.Timing(500, 5, 30));
    }

    /** Core's side of the protocol, scripted: collects every uplink message. */
    private BlockingQueue<String[]> core() throws Exception {
        BlockingQueue<String[]> uplink = new LinkedBlockingQueue<>();
        core = new MqttClient(broker.coreUri(), "phoenix-core-test", new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(MosquittoBroker.CORE_USER);
        options.setPassword(broker.password(MosquittoBroker.CORE_USER).toCharArray());
        options.setCleanSession(true);
        core.connect(options);
        core.subscribe("phoenix/v1/nodes/+/up/#", 1, (topic, message) ->
                uplink.add(new String[] {topic, new String(message.getPayload(), StandardCharsets.UTF_8)}));
        return uplink;
    }

    private void publish(String kind, String body) throws Exception {
        MqttMessage message = new MqttMessage(body.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        core.publish("phoenix/v1/nodes/" + DEVICE + "/down/" + kind, message);
    }

    /** The next uplink message of {@code kind}, skipping others. */
    private static Map<String, Object> next(BlockingQueue<String[]> uplink, String kind) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String[] message = uplink.poll(200, TimeUnit.MILLISECONDS);
            if (message != null && message[0].equals("phoenix/v1/nodes/" + DEVICE + "/up/" + kind)) {
                return Json.parseObject(message[1]);
            }
        }
        throw new AssertionError("no " + kind + " message");
    }

    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    @Test
    void registersAndRunsATaskOverTlsThroughTheShippedAcl() throws Exception {
        BlockingQueue<String[]> uplink = core();
        agent = agent("localhost", broker.password(DEVICE), broker.caFile());
        agent.start(fatal::add);

        Map<String, Object> status = next(uplink, "status");
        assertEquals("online", status.get("state"));

        Map<String, Object> register = next(uplink, "register");
        assertEquals("LINUX", register.get("hardwareClass"));
        assertEquals(List.of("attempt-id"), register.get("protocolFeatures"));
        assertTrue((Long) register.get("memoryMb") > 0);

        publish("registered", "{\"requestId\":\"" + register.get("requestId") + "\",\"status\":\"ok\","
                + "\"nodeId\":\"node-it\",\"sessionToken\":\"tok-it\",\"heartbeatIntervalSeconds\":5,"
                + "\"coreInstanceId\":\"core-it\",\"protocolVersion\":1}");

        Map<String, Object> heartbeat = next(uplink, "heartbeat");
        assertEquals("tok-it", heartbeat.get("sessionToken"));
        assertTrue((Long) heartbeat.get("availableRamMb") > 0);

        String task = "{\"taskId\":\"task-001\",\"type\":\"Monte-Carlo-Pi\",\"samples\":200000,"
                + "\"attemptId\":\"7b1adffa-a4c8-4621-90e3-293d26b907b3\",\"attemptNumber\":1}";
        publish("task", task);

        Map<String, Object> ack = next(uplink, "ack");
        Map<String, Object> started = next(uplink, "started");
        Map<String, Object> result = next(uplink, "result");
        for (Map<String, Object> report : List.of(ack, started, result)) {
            assertEquals("tok-it", report.get("sessionToken"));
            assertEquals("task-001", report.get("taskId"));
            assertEquals("7b1adffa-a4c8-4621-90e3-293d26b907b3", report.get("attemptId"));
        }
        assertEquals("SUCCESS", result.get("status"));
        assertTrue(((String) result.get("outputData")).startsWith("Computed Monte-Carlo pi ~= 3.1"));

        // QoS 1 redelivery: acknowledged again, the known result re-sent, not run twice.
        publish("task", task);
        next(uplink, "ack");
        assertEquals(result, next(uplink, "result"));
        assertEquals(1, logs.stream().filter(l -> l.startsWith("Received task task-001 ")).count());

        agent.stop();
        Map<String, Object> goodbye = next(uplink, "status");
        assertEquals("offline", goodbye.get("state"));
        assertTrue(fatal.isEmpty());
    }

    @Test
    void aWrongPasswordIsRefusedAndNotRetried() throws Exception {
        agent = agent("localhost", "not-the-password", broker.caFile());
        agent.start(fatal::add);

        assertTrue(eventually(() -> !fatal.isEmpty()), String.join("\n", logs));
        assertEquals("Could not connect to the broker: the broker refused this device's user name or password",
                fatal.get(0));
        assertFalse(agent.isRunning());
    }

    @Test
    void aBrokerSignedByAnotherCaIsRefused() throws Exception {
        agent = agent("localhost", broker.password(DEVICE), broker.otherCa());
        agent.start(fatal::add);

        assertTrue(eventually(() -> !fatal.isEmpty()), String.join("\n", logs));
        assertTrue(fatal.get(0).contains("the broker certificate is not trusted"), fatal.get(0));
    }

    @Test
    void aHostNameNotInTheBrokerCertificateIsRefused() throws Exception {
        // The certificate names only "localhost".
        agent = agent("127.0.0.1", broker.password(DEVICE), broker.caFile());
        agent.start(fatal::add);

        assertTrue(eventually(() -> !fatal.isEmpty()), String.join("\n", logs));
        assertTrue(fatal.get(0).contains("does not match the host name in ssl://127.0.0.1:"), fatal.get(0));
    }

    @Test
    void anotherDevicesCredentialsCannotReadThisDevicesTasks() throws Exception {
        core();
        BlockingQueue<String> intruder = new LinkedBlockingQueue<>();
        MqttClient other = new MqttClient("ssl://localhost:" + broker.tlsPort(), "linux-other", new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName("linux-other");
        options.setPassword(broker.password("linux-other").toCharArray());
        options.setSocketFactory(MqttTls.socketFactory(broker.caFile()));
        options.setHttpsHostnameVerificationEnabled(true);
        other.connect(options);
        try {
            try {
                other.subscribe("phoenix/v1/nodes/" + DEVICE + "/down/#", 1,
                        (topic, message) -> intruder.add(topic));
            } catch (org.eclipse.paho.client.mqttv3.MqttException refused) {
                return; // the broker refused the subscription outright
            }
            publish("task", "{\"taskId\":\"t\",\"attemptId\":\"a\"}");
            assertEquals(null, intruder.poll(1, TimeUnit.SECONDS));
        } finally {
            other.disconnect();
            other.close();
        }
    }
}

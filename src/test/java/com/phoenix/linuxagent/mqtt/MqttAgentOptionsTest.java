// SPDX-License-Identifier: Apache-2.0

package com.phoenix.linuxagent.mqtt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MqttAgentOptionsTest {

    @TempDir
    Path dir;

    private Path file(String name, String content) throws IOException {
        Path path = dir.resolve(name);
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    @Test
    void brokerAddressesAreNormalizedToTls() {
        assertEquals("ssl://broker.lan:8883", MqttAgentOptions.normalizeBrokerUri("broker.lan"));
        assertEquals("ssl://broker.lan:9883", MqttAgentOptions.normalizeBrokerUri("broker.lan:9883"));
        assertEquals("ssl://192.168.1.20:8883", MqttAgentOptions.normalizeBrokerUri(" ssl://192.168.1.20:8883 "));
        assertEquals("ssl://broker.lan:8883", MqttAgentOptions.normalizeBrokerUri("mqtts://broker.lan"));
        assertEquals("ssl://broker.lan:8883", MqttAgentOptions.normalizeBrokerUri("SSL://broker.lan/"));
        assertEquals("ssl://[fd00::1]:8883", MqttAgentOptions.normalizeBrokerUri("[fd00::1]"));
    }

    @Test
    void plainTcpIsOnlyForALocalBroker() {
        assertEquals("tcp://127.0.0.1:1883", MqttAgentOptions.normalizeBrokerUri("tcp://127.0.0.1"));
        assertEquals("tcp://localhost:1884", MqttAgentOptions.normalizeBrokerUri("tcp://localhost:1884"));
        assertEquals("tcp://[::1]:1883", MqttAgentOptions.normalizeBrokerUri("mqtt://[::1]"));

        assertThrows(IllegalArgumentException.class, () -> MqttAgentOptions.normalizeBrokerUri("tcp://broker.lan"));
        assertThrows(IllegalArgumentException.class, () -> MqttAgentOptions.normalizeBrokerUri("tcp://192.168.1.20"));
    }

    @Test
    void unusableBrokerAddressesAreRefused() {
        for (String bad : List.of("", "  ", "http://broker.lan", "ssl://", "ssl://user:pw@broker.lan",
                "ssl://broker.lan:8883/path", "ssl://broker.lan:8883?x=1", "ssl://broker.lan:99999")) {
            assertThrows(IllegalArgumentException.class, () -> MqttAgentOptions.normalizeBrokerUri(bad), bad);
        }
    }

    @Test
    void parsesEveryOption() throws IOException {
        Path ca = file("ca.pem", "x");
        Path password = file("password", "  s3cret-pass\n");
        Path token = file("token", "enroll-me\n");

        MqttAgentOptions options = MqttAgentOptions.parse(List.of(
                "--broker", "broker.lan",
                "--ca", ca.toString(),
                "--password-file", password.toString(),
                "--enrollment-token-file=" + token,
                "--device-id-file", dir.resolve("id").toString(),
                "--display-name", "lab-pi-01",
                "--capability", "arm",
                "--capability=low-power",
                "--topic-prefix", "lab/phoenix",
                "--validation-hold", "30"), Map.of());

        assertEquals("ssl://broker.lan:8883", options.brokerUri);
        assertEquals(ca, options.caFile);
        assertArrayEquals("s3cret-pass".toCharArray(), options.password);
        assertEquals("enroll-me", options.enrollmentToken);
        assertEquals(dir.resolve("id"), options.deviceIdFile);
        assertEquals("lab-pi-01", options.displayName);
        assertEquals(List.of("arm", "low-power"), options.capabilities);
        assertEquals("lab/phoenix", options.topicPrefix);
        assertEquals(30, options.validationHoldSeconds);
        assertTrue(options.usesTls());
    }

    @Test
    void secretsMayComeFromTheEnvironment() {
        MqttAgentOptions options = MqttAgentOptions.parse(List.of("--broker", "broker.lan"),
                Map.of("PHOENIX_MQTT_PASSWORD", "env-pass", "PHOENIX_MQTT_ENROLLMENT_TOKEN", "env-token"));

        assertArrayEquals("env-pass".toCharArray(), options.password);
        assertEquals("env-token", options.enrollmentToken);
        assertEquals(LinuxHost.DEFAULT_DEVICE_ID_FILE, options.deviceIdFile);
        assertEquals("phoenix", options.topicPrefix);
        assertEquals(0, options.validationHoldSeconds);
        assertNull(options.caFile);
        assertNull(options.displayName);
    }

    @Test
    void aPasswordIsRequired() throws IOException {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> MqttAgentOptions.parse(List.of("--broker", "broker.lan"), Map.of()));
        assertTrue(missing.getMessage().contains("password"));

        Path empty = file("empty", " \n");
        assertThrows(IllegalArgumentException.class, () -> MqttAgentOptions.parse(
                List.of("--broker", "broker.lan", "--password-file", empty.toString()), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> MqttAgentOptions.parse(
                List.of("--broker", "broker.lan", "--password-file", dir.resolve("absent").toString()), Map.of()));
    }

    @Test
    void mistakesAreReportedNotIgnored() throws IOException {
        Map<String, String> env = Map.of("PHOENIX_MQTT_PASSWORD", "pw");
        Path ca = file("ca.pem", "x");

        for (List<String> args : List.of(
                List.of("--password-file", "x"),                                    // no broker
                List.of("--broker"),                                                // no value
                List.of("--broker", "broker.lan", "--frobnicate"),                  // unknown option
                List.of("--broker", "broker.lan", "--validation-hold", "301"),
                List.of("--broker", "broker.lan", "--validation-hold", "-1"),
                List.of("--broker", "broker.lan", "--validation-hold", "soon"),
                List.of("--broker", "broker.lan", "--topic-prefix", "phoenix/#"),
                List.of("--broker", "broker.lan", "--topic-prefix", "/phoenix"),
                List.of("--broker", "broker.lan", "--capability", "has space"),
                List.of("--broker", "broker.lan", "--display-name", " "),
                List.of("--broker", "broker.lan", "--ca", dir.resolve("absent.pem").toString()),
                List.of("--broker", "tcp://127.0.0.1", "--ca", ca.toString()))) {
            assertThrows(IllegalArgumentException.class, () -> MqttAgentOptions.parse(args, env), args.toString());
        }
    }

    @Test
    void secretsNeverAppearInToString() {
        String text = MqttAgentOptions.parse(List.of("--broker", "broker.lan"),
                Map.of("PHOENIX_MQTT_PASSWORD", "s3cret-pass", "PHOENIX_MQTT_ENROLLMENT_TOKEN", "enroll-me"))
                .toString();

        assertFalse(text.contains("s3cret-pass"));
        assertFalse(text.contains("enroll-me"));
        assertTrue(text.contains("ssl://broker.lan:8883"));
    }

    @Test
    void homeIsExpanded() {
        assertEquals(Path.of(System.getProperty("user.home"), "a", "b"), MqttAgentOptions.expandHome("~/a/b"));
        assertEquals(Path.of("/etc/x"), MqttAgentOptions.expandHome("/etc/x"));
    }
}

package com.phoenix.linuxagent;

import java.io.IOException;
import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.phoenix.linuxagent.mqtt.LinuxHost;
import com.phoenix.linuxagent.mqtt.MqttAgentOptions;
import com.phoenix.linuxagent.mqtt.MqttNodeAgent;
import com.phoenix.linuxagent.mqtt.MqttTls;

/**
 * Entry point of {@code phoenix-linux-agent.jar}.
 *
 * <pre>
 *   java -jar phoenix-linux-agent.jar [CORE_URL] [CALLBACK_PORT]   HTTP push (v0.2 baseline)
 *   java -jar phoenix-linux-agent.jar --broker URI [options]       MQTT/TLS
 *   java -jar phoenix-linux-agent.jar --print-device-id [--device-id-file FILE]
 * </pre>
 *
 * <p>Exit codes: 0 after Ctrl+C, 1 when the MQTT agent gave up (refused
 * credentials, untrusted broker, refused registration), 2 for unusable options.
 */
public final class Main {

    static final int EXIT_GAVE_UP = 1;
    static final int EXIT_USAGE = 2;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        List<String> arguments = Arrays.asList(args);

        if (arguments.contains("--help") || arguments.contains("-h")) {
            usage(System.out);
            return;
        }
        if (arguments.contains("--version")) {
            System.out.println("Phoenix Linux Node Agent " + version());
            return;
        }
        if (arguments.contains("--print-device-id")) {
            System.exit(printDeviceId(arguments));
            return;
        }

        boolean mqtt = arguments.stream().anyMatch(a -> a.startsWith("--"));
        if (!mqtt) {
            // The HTTP push agent, unchanged: [CORE_URL] [CALLBACK_PORT].
            LinuxNodeAgent.main(args);
            return;
        }

        System.exit(runMqtt(arguments));
    }

    private static int runMqtt(List<String> arguments) throws InterruptedException {
        MqttAgentOptions options;
        String deviceId;
        LinuxHost host = new LinuxHost();
        try {
            options = MqttAgentOptions.parse(arguments, System.getenv());
            deviceId = LinuxHost.loadOrCreateDeviceId(options.deviceIdFile);
            host.totalRamMb(); // fails fast where /proc is missing
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("phoenix-linux-agent: " + e.getMessage());
            return EXIT_USAGE;
        } catch (IOException e) {
            System.err.println("phoenix-linux-agent: cannot use the device id file: " + e.getMessage());
            return EXIT_USAGE;
        }

        Consumer<String> log = Main::log;
        log.accept("Phoenix Linux Node Agent " + version() + " (MQTT/TLS, protocol v"
                + MqttNodeAgent.PROTOCOL_VERSION + ")");
        log.accept("Device id " + deviceId + " (broker user name and client id; stored in "
                + options.deviceIdFile + ")");
        log.accept("Host " + (options.displayName != null ? options.displayName : host.hostName()) + ": "
                + host.architecture() + ", " + host.cpuCores() + " CPU cores, " + host.totalRamMb() + " MB RAM, Java "
                + System.getProperty("java.version"));
        if (options.caFile != null) {
            try {
                log.accept("Trusting the broker CA " + MqttTls.describe(options.caFile));
            } catch (IllegalArgumentException e) {
                System.err.println("phoenix-linux-agent: " + e.getMessage());
                return EXIT_USAGE;
            }
        } else if (options.usesTls()) {
            log.accept("No --ca given: trusting the Java runtime's CA store.");
        }

        MqttNodeAgent agent = new MqttNodeAgent(options, deviceId, host, log);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> gaveUp = new AtomicReference<>();

        Thread shutdown = new Thread(() -> {
            if (agent.isRunning()) {
                log.accept("Stopping...");
                agent.stop();
            }
            done.countDown();
        }, "phoenix-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);

        try {
            agent.start(reason -> {
                gaveUp.set(reason);
                done.countDown();
            });
        } catch (IllegalArgumentException e) {
            System.err.println("phoenix-linux-agent: " + e.getMessage());
            return EXIT_USAGE;
        }

        done.await();
        if (gaveUp.get() != null) {
            log.accept("Giving up: " + gaveUp.get());
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException e) {
                // Already shutting down.
            }
            return EXIT_GAVE_UP;
        }
        return 0;
    }

    private static int printDeviceId(List<String> arguments) {
        int index = arguments.indexOf("--device-id-file");
        try {
            java.nio.file.Path file = index >= 0 && index + 1 < arguments.size()
                    ? MqttAgentOptions.expandHome(arguments.get(index + 1))
                    : LinuxHost.DEFAULT_DEVICE_ID_FILE;
            System.out.println(LinuxHost.loadOrCreateDeviceId(file));
            return 0;
        } catch (IOException | IllegalStateException e) {
            System.err.println("phoenix-linux-agent: " + e.getMessage());
            return EXIT_USAGE;
        }
    }

    static synchronized void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] [LinuxNodeAgent] " + message);
    }

    static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "(development build)" : "v" + version;
    }

    private static void usage(PrintStream out) {
        out.println("Phoenix Linux Node Agent " + version());
        out.println();
        out.println("HTTP push (v0.2 baseline):");
        out.println("  java -jar phoenix-linux-agent.jar [CORE_URL] [CALLBACK_PORT]");
        out.println("      CORE_URL       default http://127.0.0.1:8080");
        out.println("      CALLBACK_PORT  default 9090");
        out.println();
        out.println("MQTT/TLS:");
        out.println("  java -jar phoenix-linux-agent.jar --broker URI --ca FILE --password-file FILE [options]");
        out.println("      --broker URI                  host, host:port or ssl://host:port (default port 8883)");
        out.println("      --ca FILE                     CA certificate (PEM) that signed the broker certificate;");
        out.println("                                    default: the Java runtime's CA store");
        out.println("      --password-file FILE          broker password (or PHOENIX_MQTT_PASSWORD)");
        out.println("      --enrollment-token-file FILE  Core's enrollment token (or PHOENIX_MQTT_ENROLLMENT_TOKEN)");
        out.println("      --device-id-file FILE         default ~/.phoenix_linux_device_id (shared with HTTP)");
        out.println("      --display-name NAME           default: the host name");
        out.println("      --capability TAG              scheduling tag; repeatable");
        out.println("      --topic-prefix PREFIX         default phoenix (Core's phoenix.mqtt.topicPrefix)");
        out.println("      --validation-hold SECONDS     validation runs only: hold each task (0-300)");
        out.println();
        out.println("Other:");
        out.println("  --print-device-id [--device-id-file FILE]   print the device id (the broker user name)");
        out.println("  --version, --help");
    }
}

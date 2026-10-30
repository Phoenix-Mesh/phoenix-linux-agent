// SPDX-License-Identifier: Apache-2.0

package com.phoenix.linuxagent.mqtt;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;

/**
 * A real Mosquitto broker for integration tests, configured like Phoenix
 * Core's {@code deploy/mosquitto}: a TLS listener with a CA-signed server
 * certificate, password authentication, {@code allow_anonymous false}, the
 * shipped ACL, and a loopback listener for Core.
 *
 * <p>The server certificate names only {@code localhost}, so connecting to
 * {@code ssl://127.0.0.1} must fail host name verification.
 *
 * <p>Tests using it are skipped when {@code mosquitto},
 * {@code mosquitto_passwd} or {@code openssl} is not installed. CI installs them.
 */
final class MosquittoBroker {

    static final String CORE_USER = "phoenix-core";

    /** The ACL Phoenix Core ships in deploy/mosquitto/acl. */
    private static final String ACL = String.join("\n",
            "user phoenix-core",
            "topic readwrite phoenix/v1/#",
            "",
            "pattern write phoenix/v1/nodes/%u/up/#",
            "pattern read phoenix/v1/nodes/%u/down/#",
            "pattern read phoenix/v1/core/status",
            "");

    private final Path dir;
    private final Map<String, String> passwords = new LinkedHashMap<>();
    private final int tlsPort;
    private final int corePort;
    private Process process;

    static void assumeInstalled() {
        for (String tool : List.of("mosquitto", "mosquitto_passwd", "openssl")) {
            Assumptions.assumeTrue(executable(tool).isPresent(),
                    tool + " is not installed; skipping the Mosquitto integration test");
        }
    }

    static MosquittoBroker start(Path dir, List<String> users) throws Exception {
        MosquittoBroker broker = new MosquittoBroker(dir, freePort(), freePort());
        broker.generate(users);
        broker.launch();
        return broker;
    }

    private MosquittoBroker(Path dir, int tlsPort, int corePort) {
        this.dir = dir;
        this.tlsPort = tlsPort;
        this.corePort = corePort;
    }

    int tlsPort() {
        return tlsPort;
    }

    /** Plain loopback listener, as Core uses in production. */
    String coreUri() {
        return "tcp://127.0.0.1:" + corePort;
    }

    Path caFile() {
        return dir.resolve("ca.pem");
    }

    String password(String user) {
        return passwords.get(user);
    }

    /** A second, unrelated CA, for "untrusted broker" tests. */
    Path otherCa() throws Exception {
        Path other = dir.resolve("other");
        Files.createDirectories(other);
        openssl(other, "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes",
                "-keyout", "ca.key", "-out", "ca.pem", "-days", "2", "-subj", "/CN=Some Other CA");
        return other.resolve("ca.pem");
    }

    private void generate(List<String> users) throws Exception {
        openssl(dir, "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes",
                "-keyout", "ca.key", "-out", "ca.pem", "-days", "2", "-subj", "/CN=Phoenix Test Broker CA",
                "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign");
        openssl(dir, "req", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes",
                "-keyout", "server.key", "-out", "server.csr", "-subj", "/CN=localhost");
        Files.writeString(dir.resolve("server.ext"), String.join("\n",
                "basicConstraints=CA:FALSE",
                "keyUsage=critical,digitalSignature,keyEncipherment",
                "extendedKeyUsage=serverAuth",
                "subjectAltName=DNS:localhost",
                ""));
        openssl(dir, "x509", "-req", "-in", "server.csr", "-CA", "ca.pem", "-CAkey", "ca.key",
                "-CAcreateserial", "-out", "server.pem", "-days", "2", "-extfile", "server.ext");

        Path passwd = dir.resolve("passwd");
        List<String> everyone = new ArrayList<>(users);
        everyone.add(0, CORE_USER);
        for (String user : everyone) {
            String password = UUID.randomUUID().toString();
            passwords.put(user, password);
            List<String> add = new ArrayList<>(List.of(executable("mosquitto_passwd").orElseThrow().toString()));
            if (!Files.exists(passwd)) {
                add.add("-c");
            }
            add.addAll(List.of("-b", passwd.toString(), user, password));
            run(dir, add);
        }

        Files.writeString(dir.resolve("acl"), ACL);
        Files.writeString(dir.resolve("mosquitto.conf"), String.join("\n",
                "per_listener_settings false",
                "allow_anonymous false",
                "password_file " + passwd,
                "acl_file " + dir.resolve("acl"),
                "persistence false",
                "max_packet_size 70000",
                "log_dest stdout",
                "log_type error",
                "log_type warning",
                "log_type notice",
                "user " + System.getProperty("user.name"),
                "",
                "listener " + corePort + " 127.0.0.1",
                "",
                "listener " + tlsPort,
                "cafile " + caFile(),
                "certfile " + dir.resolve("server.pem"),
                "keyfile " + dir.resolve("server.key"),
                "tls_version tlsv1.2",
                ""));
    }

    private void launch() throws Exception {
        process = new ProcessBuilder(executable("mosquitto").orElseThrow().toString(),
                "-c", dir.resolve("mosquitto.conf").toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("mosquitto.log").toFile()))
                .start();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("mosquitto exited: " + log());
            }
            if (accepts(tlsPort) && accepts(corePort)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("mosquitto did not start: " + log());
    }

    String log() {
        try {
            return Files.readString(dir.resolve("mosquitto.log"));
        } catch (IOException e) {
            return "(no log)";
        }
    }

    void close() throws InterruptedException {
        if (process != null) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
            process = null;
        }
    }

    private static void openssl(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable("openssl").orElseThrow().toString());
        command.addAll(List.of(args));
        run(dir, command);
    }

    private static void run(Path dir, List<String> command) throws Exception {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
        }
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static Optional<Path> executable(String name) {
        List<String> dirs = new ArrayList<>(List.of(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)));
        dirs.addAll(List.of("/usr/sbin", "/usr/local/sbin", "/sbin"));
        for (String d : dirs) {
            if (d.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(d, name);
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}

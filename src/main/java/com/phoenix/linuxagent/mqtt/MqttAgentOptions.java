package com.phoenix.linuxagent.mqtt;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The MQTT/TLS agent's settings, from command-line options.
 *
 * <pre>
 *   --broker URI                 required: host, host:port, ssl://host:port or mqtts://host:port
 *   --ca FILE                    CA certificate(s) the broker certificate was signed with (PEM)
 *   --password-file FILE         broker password (or PHOENIX_MQTT_PASSWORD)
 *   --enrollment-token-file FILE Core's enrollment token (or PHOENIX_MQTT_ENROLLMENT_TOKEN)
 *   --device-id-file FILE        default ~/.phoenix_linux_device_id
 *   --display-name NAME          default: the host name
 *   --capability TAG             scheduling tag; repeatable
 *   --topic-prefix PREFIX        default phoenix
 *   --validation-hold SECONDS    0-300, default 0 (validation runs only)
 * </pre>
 *
 * <p>The broker address is normalized to {@code ssl://host:port} (default
 * port 8883). Plain {@code tcp://} is accepted only for a broker on this
 * machine, as in Phoenix Core.
 */
public final class MqttAgentOptions {

    public static final int DEFAULT_TLS_PORT = 8883;
    public static final int MAX_VALIDATION_HOLD_SECONDS = 300;

    private static final Pattern TOPIC_PREFIX = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");
    private static final Pattern CAPABILITY = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    public final String brokerUri;
    public final Path caFile;
    public final char[] password;
    public final String enrollmentToken;
    public final Path deviceIdFile;
    public final String displayName;
    public final List<String> capabilities;
    public final String topicPrefix;
    public final int validationHoldSeconds;

    private MqttAgentOptions(Builder b) {
        this.brokerUri = normalizeBrokerUri(b.broker);
        this.caFile = b.caFile;
        this.password = b.password;
        this.enrollmentToken = b.enrollmentToken;
        this.deviceIdFile = b.deviceIdFile == null ? LinuxHost.DEFAULT_DEVICE_ID_FILE : b.deviceIdFile;
        this.displayName = b.displayName;
        this.capabilities = Collections.unmodifiableList(new ArrayList<>(b.capabilities));
        this.topicPrefix = b.topicPrefix;
        this.validationHoldSeconds = b.validationHoldSeconds;

        if (password == null || password.length == 0) {
            throw new IllegalArgumentException(
                    "A broker password is required: --password-file FILE or PHOENIX_MQTT_PASSWORD.");
        }
        if (caFile != null && !brokerUri.startsWith("ssl://")) {
            throw new IllegalArgumentException("--ca is only used with a TLS (ssl://) broker.");
        }
        if (!TOPIC_PREFIX.matcher(topicPrefix).matches()) {
            throw new IllegalArgumentException("--topic-prefix " + topicPrefix
                    + " is not usable: levels of [A-Za-z0-9._-] separated by /, without wildcards.");
        }
        if (validationHoldSeconds < 0 || validationHoldSeconds > MAX_VALIDATION_HOLD_SECONDS) {
            throw new IllegalArgumentException(
                    "--validation-hold must be between 0 and " + MAX_VALIDATION_HOLD_SECONDS + " seconds.");
        }
        if (displayName != null && displayName.isBlank()) {
            throw new IllegalArgumentException("--display-name must not be blank.");
        }
        for (String capability : capabilities) {
            if (!CAPABILITY.matcher(capability).matches()) {
                throw new IllegalArgumentException("--capability " + capability
                        + " is not usable: 1-64 characters of [A-Za-z0-9._:-].");
            }
        }
    }

    public boolean usesTls() {
        return brokerUri.startsWith("ssl://");
    }

    /**
     * Parses the options after the transport was chosen.
     *
     * @param environment where {@code PHOENIX_MQTT_PASSWORD} and
     *                    {@code PHOENIX_MQTT_ENROLLMENT_TOKEN} are looked up
     * @throws IllegalArgumentException with a message for the user
     */
    public static MqttAgentOptions parse(List<String> args, Map<String, String> environment) {
        Builder b = new Builder();
        String passwordFile = null;
        String tokenFile = null;

        for (int i = 0; i < args.size(); i++) {
            String option = args.get(i);
            String value;
            int eq = option.indexOf('=');
            if (option.startsWith("--") && eq > 0) {
                value = option.substring(eq + 1);
                option = option.substring(0, eq);
            } else {
                value = null;
            }

            switch (option) {
                case "--broker":
                    b.broker = value != null ? value : next(args, ++i, option);
                    break;
                case "--ca":
                    b.caFile = existingFile(value != null ? value : next(args, ++i, option), option);
                    break;
                case "--password-file":
                    passwordFile = value != null ? value : next(args, ++i, option);
                    break;
                case "--enrollment-token-file":
                    tokenFile = value != null ? value : next(args, ++i, option);
                    break;
                case "--device-id-file":
                    b.deviceIdFile = expandHome(value != null ? value : next(args, ++i, option));
                    break;
                case "--display-name":
                    b.displayName = value != null ? value : next(args, ++i, option);
                    break;
                case "--capability":
                    b.capabilities.add(value != null ? value : next(args, ++i, option));
                    break;
                case "--topic-prefix":
                    b.topicPrefix = value != null ? value : next(args, ++i, option);
                    break;
                case "--validation-hold":
                    String hold = value != null ? value : next(args, ++i, option);
                    try {
                        b.validationHoldSeconds = Integer.parseInt(hold.trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--validation-hold needs a number of seconds, not " + hold);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown option " + args.get(i) + " (see --help).");
            }
        }

        if (b.broker == null) {
            throw new IllegalArgumentException("--broker is required for the MQTT/TLS transport.");
        }

        String password = passwordFile != null
                ? readSecret(passwordFile, "--password-file")
                : trimToNull(environment.get("PHOENIX_MQTT_PASSWORD"));
        b.password = password == null ? null : password.toCharArray();

        b.enrollmentToken = tokenFile != null
                ? readSecret(tokenFile, "--enrollment-token-file")
                : trimToNull(environment.get("PHOENIX_MQTT_ENROLLMENT_TOKEN"));

        return new MqttAgentOptions(b);
    }

    /**
     * {@code ssl://host:port} for anything the user may type, {@code tcp://}
     * kept only for a broker on this machine.
     *
     * @throws IllegalArgumentException if the address is not usable
     */
    public static String normalizeBrokerUri(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("--broker is empty.");
        }
        String text = input.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        String scheme;
        if (lower.startsWith("ssl://") || lower.startsWith("mqtts://")) {
            scheme = "ssl";
        } else if (lower.startsWith("tcp://") || lower.startsWith("mqtt://")) {
            scheme = "tcp";
        } else if (lower.contains("://")) {
            throw new IllegalArgumentException("Broker address " + text + ": use ssl://host:port.");
        } else {
            scheme = "ssl";
            text = "ssl://" + text;
        }

        URI uri;
        try {
            uri = new URI(text.substring(0, text.indexOf("://")).toLowerCase(Locale.ROOT)
                    + text.substring(text.indexOf("://")));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Broker address " + input + " is not valid: " + e.getMessage());
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Broker address " + input + " has no host name.");
        }
        if (uri.getRawUserInfo() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                && !uri.getRawPath().equals("/")) || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Broker address " + input + ": give only host and port.");
        }
        int port = uri.getPort();
        if (port == -1) {
            port = scheme.equals("ssl") ? DEFAULT_TLS_PORT : 1883;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Broker address " + input + " has an invalid port.");
        }
        if (scheme.equals("tcp") && !isLoopback(host)) {
            throw new IllegalArgumentException("Plain tcp:// is only allowed for a broker on this machine; "
                    + "use ssl://" + host + ":" + DEFAULT_TLS_PORT + ".");
        }
        return scheme + "://" + host + ":" + port;
    }

    private static boolean isLoopback(String host) {
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!literal.matches("[0-9.]+") && !literal.contains(":")) {
            return false; // a name: never resolved here
        }
        try {
            return InetAddress.getByName(literal).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    private static String next(List<String> args, int index, String option) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(option + " needs a value.");
        }
        return args.get(index);
    }

    private static Path existingFile(String path, String option) {
        Path file = expandHome(path);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IllegalArgumentException(option + " " + path + ": no readable file there.");
        }
        return file;
    }

    private static String readSecret(String path, String option) {
        Path file = existingFile(path, option);
        try {
            String value = trimToNull(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (value == null) {
                throw new IllegalArgumentException(option + " " + path + " is empty.");
            }
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException(option + " " + path + ": " + e.getMessage());
        }
    }

    /** {@code ~} and {@code ~/...} relative to the user's home directory. */
    public static Path expandHome(String path) {
        if (path.equals("~")) {
            return Paths.get(System.getProperty("user.home"));
        }
        if (path.startsWith("~/")) {
            return Paths.get(System.getProperty("user.home"), path.substring(2));
        }
        return Paths.get(path);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Secrets are never printed. */
    @Override
    public String toString() {
        return "MqttAgentOptions(broker=" + brokerUri + ", ca=" + caFile + ", password=***, enrollmentToken="
                + (enrollmentToken == null ? "none" : "***") + ", deviceIdFile=" + deviceIdFile
                + ", displayName=" + displayName + ", capabilities=" + capabilities
                + ", topicPrefix=" + topicPrefix + ", validationHold=" + validationHoldSeconds + "s)";
    }

    /** For tests and for {@link #parse}. */
    public static final class Builder {

        String broker;
        Path caFile;
        char[] password;
        String enrollmentToken;
        Path deviceIdFile;
        String displayName;
        final List<String> capabilities = new ArrayList<>();
        String topicPrefix = "phoenix";
        int validationHoldSeconds;

        public Builder broker(String value) {
            broker = value;
            return this;
        }

        public Builder password(String value) {
            password = value.toCharArray();
            return this;
        }

        public Builder enrollmentToken(String value) {
            enrollmentToken = value;
            return this;
        }

        public Builder caFile(Path value) {
            caFile = value;
            return this;
        }

        public Builder deviceIdFile(Path value) {
            deviceIdFile = value;
            return this;
        }

        public Builder displayName(String value) {
            displayName = value;
            return this;
        }

        public Builder capability(String value) {
            capabilities.add(value);
            return this;
        }

        public Builder topicPrefix(String value) {
            topicPrefix = value;
            return this;
        }

        public Builder validationHoldSeconds(int value) {
            validationHoldSeconds = value;
            return this;
        }

        public MqttAgentOptions build() {
            return new MqttAgentOptions(this);
        }
    }
}

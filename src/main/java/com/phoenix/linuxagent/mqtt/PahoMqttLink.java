// SPDX-License-Identifier: Apache-2.0

package com.phoenix.linuxagent.mqtt;

import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * {@link MqttLink} on Eclipse Paho 1.2.5, the client Phoenix Core uses.
 *
 * <ul>
 *     <li>MQTT 3.1.1, persistent session ({@code cleanSession=false}), so tasks
 *         published while the machine is briefly away are queued by the broker.</li>
 *     <li>Retained last will on {@code up/status}, as the protocol requires.</li>
 *     <li>TLS through {@link MqttTls}; the broker's host name is verified
 *         against its certificate during the handshake.</li>
 *     <li>The first connection is retried here with a capped back-off; once
 *         connected, Paho reconnects on its own. Refused credentials and
 *         failed TLS verification are reported as fatal instead of being
 *         retried forever.</li>
 *     <li>Reports published while reconnecting are buffered in memory and
 *         sent when the connection returns.</li>
 * </ul>
 */
public final class PahoMqttLink implements MqttLink {

    private static final long FIRST_RETRY_MS = 1_000;
    private static final long MAX_RETRY_MS = 30_000;
    private static final int CONNECT_TIMEOUT_S = 10;
    private static final int BUFFER_SIZE = 200;
    private static final int MAX_INFLIGHT = 100;
    private static final long CLOSE_TIMEOUT_MS = 2_000;

    private final Settings settings;

    private volatile MqttAsyncClient client;
    private volatile Listener listener;
    private volatile boolean closed;

    private long retryDelayMs = FIRST_RETRY_MS;

    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "phoenix-mqtt-connect");
        thread.setDaemon(true);
        return thread;
    });

    public PahoMqttLink(Settings settings) {
        this.settings = settings;
    }

    @Override
    public boolean isConnected() {
        MqttAsyncClient current = client;
        return current != null && current.isConnected();
    }

    @Override
    public void open(Listener listener) {
        this.listener = listener;

        MqttConnectOptions options = connectOptions(); // throws IllegalArgumentException for a bad CA file

        MqttAsyncClient created;
        try {
            created = new MqttAsyncClient(settings.brokerUri, settings.clientId, new MemoryPersistence());
        } catch (MqttException | IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Cannot use broker address " + settings.brokerUri + ": " + e.getMessage(), e);
        }

        created.setCallback(new Callback());
        DisconnectedBufferOptions buffer = new DisconnectedBufferOptions();
        buffer.setBufferEnabled(true);
        buffer.setBufferSize(BUFFER_SIZE);
        buffer.setPersistBuffer(false);
        buffer.setDeleteOldestMessages(false);
        created.setBufferOpts(buffer);

        client = created;
        attempt(created, options);
    }

    @Override
    public boolean subscribe(List<String> filters, int qos) {
        MqttAsyncClient current = client;
        if (current == null) {
            return false;
        }
        int[] qoss = new int[filters.size()];
        java.util.Arrays.fill(qoss, qos);
        try {
            current.subscribe(filters.toArray(new String[0]), qoss);
            return true;
        } catch (MqttException e) {
            return false;
        }
    }

    @Override
    public boolean publish(Message message) {
        MqttAsyncClient current = client;
        if (current == null || closed) {
            return false;
        }
        try {
            current.publish(message.topic, message.payload.getBytes(StandardCharsets.UTF_8),
                    message.qos, message.retained);
            return true;
        } catch (MqttException e) {
            return false;
        }
    }

    @Override
    public void close(Message farewell) {
        if (closed) {
            return;
        }
        closed = true;
        retries.shutdownNow();

        MqttAsyncClient current = client;
        if (current == null) {
            return;
        }

        try {
            if (current.isConnected()) {
                if (farewell != null) {
                    current.publish(farewell.topic, farewell.payload.getBytes(StandardCharsets.UTF_8),
                            farewell.qos, farewell.retained).waitForCompletion(CLOSE_TIMEOUT_MS);
                }
                current.disconnect(CLOSE_TIMEOUT_MS / 2).waitForCompletion(CLOSE_TIMEOUT_MS);
            }
        } catch (MqttException e) {
            // Closing anyway.
        }

        try {
            current.close();
        } catch (MqttException e) {
            // Already closed, or still busy; nothing more to do.
        }
    }

    private MqttConnectOptions connectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setCleanSession(false);
        options.setAutomaticReconnect(true);
        options.setMaxReconnectDelay((int) MAX_RETRY_MS);
        options.setKeepAliveInterval(settings.keepAliveSeconds);
        options.setConnectionTimeout(CONNECT_TIMEOUT_S);
        options.setMaxInflight(MAX_INFLIGHT);
        options.setUserName(settings.username);
        options.setPassword(settings.password.clone());
        options.setWill(settings.will.topic, settings.will.payload.getBytes(StandardCharsets.UTF_8),
                settings.will.qos, settings.will.retained);

        if (settings.usesTls()) {
            options.setSocketFactory(MqttTls.socketFactory(settings.caFile));
            // The JDK checks the broker's certificate against the host name
            // in the URI (DNS name or IP address) during the handshake.
            options.setHttpsHostnameVerificationEnabled(true);
        }
        return options;
    }

    /** One connection attempt; the first connection is retried here, later ones by Paho. */
    private void attempt(MqttAsyncClient target, MqttConnectOptions options) {
        if (closed) {
            return;
        }
        try {
            target.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    retryDelayMs = FIRST_RETRY_MS;
                    // Callback.connectComplete reports the connection.
                }

                @Override
                public void onFailure(IMqttToken token, Throwable exception) {
                    failed(target, options, exception);
                }
            });
        } catch (MqttException e) {
            failed(target, options, e);
        }
    }

    private void failed(MqttAsyncClient target, MqttConnectOptions options, Throwable error) {
        if (closed) {
            return;
        }

        boolean fatal = isFatal(error);
        Listener current = listener;
        if (current != null) {
            current.connectFailed(describe(error, settings.brokerUri, settings.clientId), fatal);
        }

        if (fatal) {
            close(null);
            return;
        }

        long delay = retryDelayMs;
        retryDelayMs = Math.min(delay * 2, MAX_RETRY_MS);
        try {
            retries.schedule(() -> attempt(target, options), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Closed meanwhile.
        }
    }

    /** Whether retrying cannot help: refused credentials or a broker TLS could not verify. */
    static boolean isFatal(Throwable error) {
        int code = reasonCode(error);
        if (code == MqttException.REASON_CODE_FAILED_AUTHENTICATION
                || code == MqttException.REASON_CODE_NOT_AUTHORIZED
                || code == MqttException.REASON_CODE_INVALID_CLIENT_ID
                || code == MqttException.REASON_CODE_INVALID_PROTOCOL_VERSION) {
            return true;
        }
        for (Throwable cause : causes(error)) {
            if (cause instanceof SSLPeerUnverifiedException
                    || cause instanceof SSLHandshakeException
                    || cause instanceof CertificateException) {
                return true;
            }
        }
        return false;
    }

    /** A sentence for the log saying why the connection failed. */
    static String describe(Throwable error, String brokerUri, String clientId) {
        int code = reasonCode(error);
        if (code == MqttException.REASON_CODE_FAILED_AUTHENTICATION
                || code == MqttException.REASON_CODE_NOT_AUTHORIZED) {
            return "the broker refused this device's user name or password";
        }
        if (code == MqttException.REASON_CODE_INVALID_CLIENT_ID) {
            return "the broker refused the client id " + clientId;
        }

        List<Throwable> chain = causes(error);
        for (Throwable cause : chain) {
            if (cause instanceof SSLPeerUnverifiedException || isHostNameMismatch(cause)) {
                return "the broker certificate does not match the host name in " + brokerUri;
            }
        }
        for (Throwable cause : chain) {
            if (cause instanceof CertificateException || cause instanceof SSLHandshakeException) {
                return "TLS failed: the broker certificate is not trusted (check the CA file)";
            }
        }
        for (Throwable cause : chain) {
            if (cause instanceof SSLException) {
                return "TLS failed: " + cause.getMessage();
            }
        }

        Throwable root = chain.isEmpty() ? null : chain.get(chain.size() - 1);
        if (root != null && root.getMessage() != null) {
            return root.getMessage();
        }
        if (error != null && error.getMessage() != null) {
            return error.getMessage();
        }
        return error == null ? "unknown error" : error.getClass().getSimpleName();
    }

    /** The JDK's host name check fails with a CertificateException saying "No ... matching ...". */
    private static boolean isHostNameMismatch(Throwable cause) {
        if (!(cause instanceof CertificateException) || cause.getMessage() == null) {
            return false;
        }
        String message = cause.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("no subject alternative") || message.contains("no name matching");
    }

    private static int reasonCode(Throwable error) {
        return error instanceof MqttException ? ((MqttException) error).getReasonCode() : -1;
    }

    private static List<Throwable> causes(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        Throwable current = error;
        while (current != null && !chain.contains(current) && chain.size() < 10) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }

    private final class Callback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverUri) {
            Listener current = listener;
            if (!closed && current != null) {
                current.connected(reconnect);
            }
        }

        @Override
        public void connectionLost(Throwable cause) {
            Listener current = listener;
            if (!closed && current != null) {
                current.connectionLost(cause == null || cause.getMessage() == null
                        ? "connection closed" : cause.getMessage());
            }
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            Listener current = listener;
            if (closed || current == null || topic == null || message == null) {
                return;
            }
            try {
                current.messageArrived(topic, message.getPayload());
            } catch (RuntimeException e) {
                // An exception here would make Paho drop the connection.
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // Nothing to do: delivery is confirmed at the application level by Core.
        }
    }
}

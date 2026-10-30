// SPDX-License-Identifier: Apache-2.0

package com.phoenix.linuxagent.mqtt;

import java.util.List;

/**
 * The MQTT connection {@link MqttNodeAgent} talks through: {@link PahoMqttLink}
 * in production, a scripted fake in unit tests.
 *
 * <p>Implementations connect in the background, reconnect on their own after
 * a lost connection, and report what happens through a {@link Listener}.
 * They never block the caller for long.
 */
public interface MqttLink {

    /** One MQTT message. */
    final class Message {

        public final String topic;
        public final String payload;
        public final int qos;
        public final boolean retained;

        public Message(String topic, String payload, int qos, boolean retained) {
            this.topic = topic;
            this.payload = payload;
            this.qos = qos;
            this.retained = retained;
        }

        @Override
        public String toString() {
            return topic + " (qos " + qos + (retained ? ", retained" : "") + "): " + payload;
        }
    }

    /** Called on the link's own threads. */
    interface Listener {

        /** Connected, the first time or again. The listener (re)subscribes. */
        void connected(boolean reconnect);

        /** The connection dropped; the link is reconnecting. */
        void connectionLost(String cause);

        /**
         * A connection attempt failed. If {@code fatal}, retrying cannot help
         * (the broker refused the credentials, or TLS could not verify the
         * broker) and the link has stopped; otherwise it retries with a back-off.
         */
        void connectFailed(String cause, boolean fatal);

        void messageArrived(String topic, byte[] payload);
    }

    boolean isConnected();

    /**
     * Starts connecting.
     *
     * @throws IllegalArgumentException for unusable settings
     */
    void open(Listener listener);

    /** Subscribes to {@code filters}; false if the request could not be made. */
    boolean subscribe(List<String> filters, int qos);

    /** Queues {@code message}; false if it could not even be queued. */
    boolean publish(Message message);

    /**
     * Publishes {@code farewell} (may be null) if still connected, then
     * disconnects for good. May block for a few seconds. Idempotent.
     */
    void close(Message farewell);

    /** Everything needed to open a link. User name and client id are the device id. */
    final class Settings {

        public final String brokerUri;
        public final String clientId;
        public final String username;
        public final char[] password;
        /** CA certificates to trust exactly, or null for the JDK's trust store. */
        public final java.nio.file.Path caFile;
        public final int keepAliveSeconds;
        public final Message will;

        public Settings(String brokerUri, String clientId, String username, char[] password,
                        java.nio.file.Path caFile, int keepAliveSeconds, Message will) {
            this.brokerUri = brokerUri;
            this.clientId = clientId;
            this.username = username;
            this.password = password.clone();
            this.caFile = caFile;
            this.keepAliveSeconds = keepAliveSeconds;
            this.will = will;
        }

        public boolean usesTls() {
            return brokerUri.startsWith("ssl://");
        }

        @Override
        public String toString() {
            return "Settings(brokerUri=" + brokerUri + ", clientId=" + clientId
                    + ", username=" + username + ", password=***, caFile=" + caFile + ")";
        }
    }
}

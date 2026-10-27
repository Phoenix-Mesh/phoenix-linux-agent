package com.phoenix.linuxagent.mqtt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS for the broker connection: which CA certificates to trust.
 *
 * <p>With a CA file, exactly the certificates in it are trusted (the
 * {@code ca.pem} from Core's {@code deploy/mosquitto/gen-certs.sh}); without
 * one, the JDK's default trust store. Protocols are whatever the JDK enables
 * for clients (TLS 1.3 and 1.2 on Java 11 and newer).
 *
 * <p>The broker's host name is checked against its certificate by
 * {@link PahoMqttLink}, during the handshake.
 */
public final class MqttTls {

    private MqttTls() {
    }

    /**
     * @param caFile PEM file of CA certificates, or null for the JDK trust store
     * @throws IllegalArgumentException if the file cannot be used
     */
    public static SSLSocketFactory socketFactory(Path caFile) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            if (caFile == null) {
                context.init(null, null, null);
                return context.getSocketFactory();
            }

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (X509Certificate certificate : certificates(caFile)) {
                trustStore.setCertificateEntry("phoenix-ca-" + index++, certificate);
            }

            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(trustStore);
            context.init(null, trust.getTrustManagers(), null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("TLS for the broker could not be set up: " + e.getMessage(), e);
        }
    }

    /**
     * The X.509 certificates in a PEM file.
     *
     * @throws IllegalArgumentException if it cannot be read or holds none
     */
    public static List<X509Certificate> certificates(Path pemFile) {
        Collection<? extends Certificate> parsed;
        try (InputStream in = Files.newInputStream(pemFile)) {
            parsed = CertificateFactory.getInstance("X.509").generateCertificates(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read the CA file " + pemFile + ": " + e.getMessage(), e);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException(
                    "The CA file " + pemFile + " is not a PEM certificate file: " + e.getMessage(), e);
        }

        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate certificate : parsed) {
            if (certificate instanceof X509Certificate) {
                certificates.add((X509Certificate) certificate);
            }
        }
        if (certificates.isEmpty()) {
            throw new IllegalArgumentException("The CA file " + pemFile + " contains no certificate.");
        }
        return certificates;
    }

    /** One line for the log: the first certificate's subject and expiry. */
    public static String describe(Path pemFile) {
        X509Certificate first = certificates(pemFile).get(0);
        return first.getSubjectX500Principal().getName() + " (valid until " + first.getNotAfter() + ")";
    }
}

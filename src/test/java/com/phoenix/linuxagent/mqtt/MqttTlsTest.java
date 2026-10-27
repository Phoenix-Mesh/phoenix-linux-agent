package com.phoenix.linuxagent.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLHandshakeException;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.Test;

class MqttTlsTest {

    private static Path resource(String name) throws URISyntaxException {
        return Path.of(MqttTlsTest.class.getResource("/" + name).toURI());
    }

    @Test
    void readsTheCaFile() throws URISyntaxException {
        Path ca = resource("test-ca.pem");

        assertEquals(1, MqttTls.certificates(ca).size());
        assertTrue(MqttTls.describe(ca).startsWith("CN=Phoenix Test CA"));
        assertNotNull(MqttTls.socketFactory(ca));
        assertNotNull(MqttTls.socketFactory(null));
    }

    @Test
    void anUnusableCaFileIsRefused() throws URISyntaxException {
        assertThrows(IllegalArgumentException.class, () -> MqttTls.certificates(resource("not-a-certificate.pem")));
        assertThrows(IllegalArgumentException.class, () -> MqttTls.socketFactory(resource("not-a-certificate.pem")));
        assertThrows(IllegalArgumentException.class, () -> MqttTls.certificates(Path.of("/nonexistent/ca.pem")));
    }

    @Test
    void refusedCredentialsAndFailedVerificationAreFatalOtherFailuresAreNot() {
        MqttException badPassword = new MqttException(MqttException.REASON_CODE_FAILED_AUTHENTICATION);
        MqttException notAuthorized = new MqttException(MqttException.REASON_CODE_NOT_AUTHORIZED);
        MqttException untrusted = new MqttException(new SSLHandshakeException("PKIX path building failed"));
        MqttException wrongHost = new MqttException(new SSLHandshakeException("No subject alternative names")
                .initCause(new CertificateException("No subject alternative names matching IP address 10.0.0.9 found")));
        MqttException refused = new MqttException(new java.net.ConnectException("Connection refused"));

        assertTrue(PahoMqttLink.isFatal(badPassword));
        assertTrue(PahoMqttLink.isFatal(notAuthorized));
        assertTrue(PahoMqttLink.isFatal(untrusted));
        assertTrue(PahoMqttLink.isFatal(wrongHost));
        assertFalse(PahoMqttLink.isFatal(refused));
        assertFalse(PahoMqttLink.isFatal(null));

        String uri = "ssl://10.0.0.9:8883";
        assertEquals("the broker refused this device's user name or password",
                PahoMqttLink.describe(badPassword, uri, "linux-x"));
        assertEquals("TLS failed: the broker certificate is not trusted (check the CA file)",
                PahoMqttLink.describe(untrusted, uri, "linux-x"));
        assertEquals("the broker certificate does not match the host name in " + uri,
                PahoMqttLink.describe(wrongHost, uri, "linux-x"));
        assertEquals("Connection refused", PahoMqttLink.describe(refused, uri, "linux-x"));
    }
}

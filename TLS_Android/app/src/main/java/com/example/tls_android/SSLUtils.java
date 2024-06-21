package com.example.tls_android;

import org.apache.commons.codec.binary.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

public class SSLUtils {

    public static KeyStore loadKeyStore(InputStream certInputStream, InputStream keyInputStream, String password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);

        // Load client certificate
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) certFactory.generateCertificate(certInputStream);

        // Load client private key
        String keyPem = new String(readAllBytes(keyInputStream));
        keyPem = keyPem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.decodeBase64(keyPem);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey key = keyFactory.generatePrivate(keySpec);

        // Add certificate and private key to KeyStore
        keyStore.setKeyEntry("client", key, password.toCharArray(), new java.security.cert.Certificate[]{cert});
        return keyStore;
    }

    public static KeyStore loadTrustStore(InputStream caInputStream) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);

        // Load CA certificate
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        X509Certificate caCert = (X509Certificate) certFactory.generateCertificate(caInputStream);

        // Add CA certificate to TrustStore
        trustStore.setCertificateEntry("ca", caCert);
        return trustStore;
    }

    private static byte[] readAllBytes(InputStream inputStream) throws Exception {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            byteArrayOutputStream.write(buffer, 0, bytesRead);
        }
        return byteArrayOutputStream.toByteArray();
    }
}

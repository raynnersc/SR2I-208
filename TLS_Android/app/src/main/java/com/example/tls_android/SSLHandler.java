package com.example.tls_android;

import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLSession;

import android.content.Context;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

public class SSLHandler {
    private static SSLEngine sslEngine;

    public static void initSSLEngine(BluetoothSocket socket, Context context) throws IOException {
        try {
            sslEngine = createSSLEngine(context);

            SSLSession session = sslEngine.getSession();
            ByteBuffer myAppData = ByteBuffer.allocate(session.getApplicationBufferSize());
            ByteBuffer myNetData = ByteBuffer.allocate(session.getPacketBufferSize());
            ByteBuffer peerAppData = ByteBuffer.allocate(session.getApplicationBufferSize());
            ByteBuffer peerNetData = ByteBuffer.allocate(session.getPacketBufferSize());

            sslEngine.beginHandshake();
            handleHandshake(socket, sslEngine, myNetData, peerNetData);
        } catch (Exception e) {
            Log.e("BluetoothConnection", "SSL Engine initialization failed: " + e.getMessage());
            throw new IOException("Failed to initialize SSL Engine", e);
        }
    }

    private static void handleHandshake(BluetoothSocket socket, SSLEngine sslEngine,
                                 ByteBuffer myNetData, ByteBuffer peerNetData) throws IOException {
        InputStream inStream = socket.getInputStream();
        OutputStream outStream = socket.getOutputStream();
        myNetData.clear();
        peerNetData.clear();

        SSLEngineResult result;
        SSLEngineResult.HandshakeStatus handshakeStatus;

        handshakeStatus = sslEngine.getHandshakeStatus();
        while (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
                handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            switch (handshakeStatus) {
                case NEED_UNWRAP:
                    if (inStream.read(peerNetData.array()) < 0) {
                        if (sslEngine.isInboundDone() && sslEngine.isOutboundDone()) {
                            return;
                        }
                        try {
                            sslEngine.closeInbound();
                        } catch (Exception e) {
                            Log.e("BluetoothConnection", "Failed to close inbound", e);
                        }
                        sslEngine.closeOutbound();
                        handshakeStatus = sslEngine.getHandshakeStatus();
                        break;
                    }
                    peerNetData.flip();
                    result = sslEngine.unwrap(peerNetData, ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize()));
                    peerNetData.compact();
                    handshakeStatus = result.getHandshakeStatus();
                    break;

                case NEED_WRAP:
                    myNetData.clear();
                    result = sslEngine.wrap(ByteBuffer.allocate(0), myNetData);
                    myNetData.flip();
                    while (myNetData.hasRemaining()) {
                        outStream.write(myNetData.array(), myNetData.position(), myNetData.limit());
                        myNetData.position(myNetData.limit());
                    }
                    handshakeStatus = result.getHandshakeStatus();
                    break;

                case NEED_TASK:
                    Runnable task;
                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        new Thread(task).start();
                    }
                    handshakeStatus = sslEngine.getHandshakeStatus();
                    break;

                case FINISHED:
                    break;

                default:
                    throw new IllegalStateException("Invalid SSL handshake status");
            }
        }
    }

    private static SSLEngine createSSLEngine(Context context) throws Exception {
        String password = "password";
        // Load PEM files
        InputStream caCertInput = context.getResources().openRawResource(R.raw.ca_cert);
        InputStream clientCertInput = context.getResources().openRawResource(R.raw.client_cert);
        InputStream clientKeyInput = context.getResources().openRawResource(R.raw.client_key);

        // Load KeyStore and TrustStore
        KeyStore keyStore = SSLUtils.loadKeyStore(clientCertInput, clientKeyInput, password);
        KeyStore trustStore = SSLUtils.loadTrustStore(caCertInput);

        // Initialize KeyManagerFactory
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password.toCharArray());

        // Initialize TrustManagerFactory
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        // Initialize SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), new SecureRandom());

        // Create and configure SSLEngine
        SSLEngine newSSLEngine = sslContext.createSSLEngine();
        newSSLEngine.setUseClientMode(true); // Set to false for server mode

        return newSSLEngine;
    }

    private void sendEncryptedData(String message, BluetoothSocket socket) throws IOException {
        ByteBuffer myAppData = ByteBuffer.wrap(message.getBytes());
        ByteBuffer myNetData = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());

        // Wrap
        myNetData.clear();
        SSLEngineResult result = sslEngine.wrap(myAppData, myNetData);
        switch (result.getStatus()) {
            case OK:
                myNetData.flip();
                while (myNetData.hasRemaining()) {
                    socket.getOutputStream().write(myNetData.array(), myNetData.position(), myNetData.limit());
                    myNetData.position(myNetData.limit());
                }
                break;
            case BUFFER_OVERFLOW:
                // Adjust the buffer size as per the SSL session needs
                break;
            case BUFFER_UNDERFLOW:
                // Should never happen during a wrap
                break;
            case CLOSED:
                throw new IOException("SSL Engine closed");
        }
    }

    private String receiveEncryptedData(BluetoothSocket socket) throws IOException {
        ByteBuffer peerNetData = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
        ByteBuffer peerAppData = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());

        // Read from socket
        if (socket.getInputStream().read(peerNetData.array()) < 0) {
            if (sslEngine.isInboundDone() && sslEngine.isOutboundDone()) {
                return null; // No more data to process
            }
            try {
                sslEngine.closeInbound();
            } catch (Exception e) {
                Log.e("BluetoothConnection", "Failed to close inbound", e);
            }
            sslEngine.closeOutbound();
            return null;
        }

        peerNetData.flip();
        SSLEngineResult result;
        while (peerNetData.hasRemaining()) {
            result = sslEngine.unwrap(peerNetData, peerAppData);
            peerNetData.compact();  // In case of partial reads
            switch (result.getStatus()) {
                case OK:
                    peerAppData.flip();
                    byte[] data = new byte[peerAppData.limit()];
                    peerAppData.get(data);
                    return new String(data);
                case BUFFER_OVERFLOW:
                    // Adjust the buffer size if there's insufficient space
                    peerAppData = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());
                    break;
                case BUFFER_UNDERFLOW:
                    // More data needed to be received from the peer
                    continue;
                case CLOSED:
                    return null; // SSL Engine has closed
            }
        }

        return null;
    }


}

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
            System.out.println("initSSLEngine - SSLEngine created");

            SSLSession session = sslEngine.getSession();
            System.out.println("initSSLEngine - session created");
            ByteBuffer myAppData = ByteBuffer.allocate(session.getApplicationBufferSize());
            ByteBuffer myNetData = ByteBuffer.allocate(session.getPacketBufferSize());
            ByteBuffer peerAppData = ByteBuffer.allocate(session.getApplicationBufferSize());
            ByteBuffer peerNetData = ByteBuffer.allocate(session.getPacketBufferSize());
//            ByteBuffer peerNetData = ByteBuffer.allocate(1024);
            System.out.println("initSSLEngine - buffers allocated");

            sslEngine.beginHandshake();
            System.out.println("initSSLEngine - beginHandshake");
            handleHandshake(socket, sslEngine, myNetData, peerNetData);
            System.out.println("initSSLEngine - Handshake created");
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

        SSLEngineResult result = null;
        SSLEngineResult.HandshakeStatus handshakeStatus;
        System.out.println("handleHandshake - inside");

        handshakeStatus = sslEngine.getHandshakeStatus();
        while (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
                handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            System.out.println("handleHandshake - inside handshake while");
            switch (handshakeStatus) {
                case NEED_UNWRAP:
                    System.out.println("handleHandshake - NEED_UNWRAP: Reading from input stream.");
                    if (peerNetData.position() == 0 || result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        int netSize = sslEngine.getSession().getPacketBufferSize();
                        // Check if the buffer is already larger than packet size, no need to recreate it.
                        if (peerNetData.capacity() < netSize) {
                            ByteBuffer b = ByteBuffer.allocate(netSize);
                            peerNetData.flip();
                            b.put(peerNetData);
                            peerNetData = b;
                            System.out.println("handleHandshake - NEED_UNWRAP: BUFFER_UNDERFLOW encountered, buffer resized.");
                        }
                        // Read more data from the input stream.
                        while (inStream.available() > 0) {
                            int bytesRead = inStream.read(peerNetData.array(), peerNetData.position(), peerNetData.remaining());
                            if (bytesRead == -1) {
                                System.out.println("handleHandshake - NEED_UNWRAP: End of stream reached, closing inbound.");
                                sslEngine.closeInbound();
                                return;
                            }
                            peerNetData.position(peerNetData.position() + bytesRead);
                        }
                    }
                    peerNetData.flip();
                    result = sslEngine.unwrap(peerNetData, myNetData);
                    System.out.println("handleHandshake - NEED_UNWRAP: Status after unwrap: " + result.getStatus());
                    peerNetData.compact();
                    handshakeStatus = result.getHandshakeStatus();
                    break;

                case NEED_WRAP:
                    System.out.println("handleHandshake - wrap (while)");
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
                    System.out.println("handleHandshake - need task (while)");
                    Runnable task;
                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        new Thread(task).start();
                    }
                    handshakeStatus = sslEngine.getHandshakeStatus();
                    break;

                case FINISHED:
                    System.out.println("handleHandshake - finished (while)");
                    break;

                default:
                    throw new IllegalStateException("Invalid SSL handshake status");
            }
        }
    }

    private static SSLEngine createSSLEngine(Context context) throws Exception {
        System.out.println("Inside createSSLEngine");
        String password = "password";
        InputStream caCertInput = null;
        InputStream clientCertInput = null;
        InputStream clientKeyInput = null;

        // Load PEM files
        try {
            caCertInput = context.getResources().openRawResource(R.raw.ca_cert);
            clientCertInput = context.getResources().openRawResource(R.raw.client_cert);
            clientKeyInput = context.getResources().openRawResource(R.raw.client_key);
        } catch (Exception e) {
            Log.e("SSLHandler", "Failed to load PEM files: " + e.getMessage());
            return null;
        }
        System.out.println("createSSLEngine - getting keys and certificates");

        // Load KeyStore and TrustStore
        KeyStore keyStore = null;
        KeyStore trustStore = null;
        try {
            keyStore = SSLUtils.loadKeyStore(clientCertInput, clientKeyInput, password);
            trustStore = SSLUtils.loadTrustStore(caCertInput);
        } catch (Exception e) {
            Log.e("SSLHandler", "Failed to load KeyStore or TrustStore: " + e.getMessage());
            return null;
        }
        System.out.println("createSSLEngine - after loading keys and certificates into KeyStore and TrustStore");

        // Initialize KeyManagerFactory
        KeyManagerFactory keyManagerFactory = null;
        try {
            keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password.toCharArray());
        } catch (Exception e) {
            Log.e("SSLHandler", "Failed to initialize KeyManagerFactory: " + e.getMessage());
            return null;
        }
        System.out.println("createSSLEngine - after initializing key Manager Factory");

        // Initialize TrustManagerFactory
        TrustManagerFactory trustManagerFactory = null;
        try {
            trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
        } catch (Exception e) {
            Log.e("SSLHandler", "Failed to initialize TrustManagerFactory: " + e.getMessage());
            return null;
        }
        System.out.println("createSSLEngine - after initializing Trust Manager Factory");

        // Initialize SSLContext
        SSLContext sslContext = null;
        try {
            sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), new SecureRandom());
        } catch (Exception e) {
            Log.e("SSLHandler", "Failed to initialize SSLContext: " + e.getMessage());
            return null;
        }
        System.out.println("createSSLEngine - SSLContext created");

        // Create and configure SSLEngine
        SSLEngine newSSLEngine = null;
        try {
            newSSLEngine = sslContext.createSSLEngine();
            newSSLEngine.setUseClientMode(true); // Set to false for server mode
        } catch (Exception e) {
            Log.e("SSLHandler", "Failed to create SSLEngine: " + e.getMessage());
            return null;
        }
        System.out.println("createSSLEngine - SSLEngine Created");

        return newSSLEngine;
    }

    public static void sendEncryptedData(String message, BluetoothSocket socket) throws IOException {
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

    public static String receiveEncryptedData(BluetoothSocket socket) throws IOException {
        System.out.println("receiveEncryptedData - inside function");
        ByteBuffer peerNetData = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
        ByteBuffer peerAppData = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());
        System.out.println("receiveEncryptedData - buffers created");

        // Read from socket
        int bytesRead = socket.getInputStream().read(peerNetData.array());
        System.out.println("receiveEncryptedData - bytesRead: " + bytesRead);

        if (bytesRead < 0) {
            System.out.println("receiveEncryptedData - reading the InputStream");
            if (sslEngine.isInboundDone() && sslEngine.isOutboundDone()) {
                System.out.println("receiveEncryptedData - no more data to process");
                return null; // No more data to process
            }
            try {
                System.out.println("receiveEncryptedData - trying to closeInbound");
                sslEngine.closeInbound();
                System.out.println("receiveEncryptedData - Inbound closed");
            } catch (Exception e) {
                Log.e("BluetoothConnection", "Failed to close inbound", e);
            }
            System.out.println("receiveEncryptedData - trying to closeOutbound");
            sslEngine.closeOutbound();
            System.out.println("receiveEncryptedData - Outbound closed");
            return null;
        }

        peerNetData.position(bytesRead);
        peerNetData.flip();
        SSLEngineResult result;
        while (peerNetData.hasRemaining()) {
            System.out.println("receiveEncryptedData - inside while");
            result = sslEngine.unwrap(peerNetData, peerAppData);
            System.out.println("receiveEncryptedData - result: " + result);
            peerNetData.compact();  // In case of partial reads
            switch (result.getStatus()) {
                case OK:
                    System.out.println("receiveEncryptedData - while: case OK");
                    peerAppData.flip();
                    byte[] data = new byte[peerAppData.limit()];
                    peerAppData.get(data);
                    return new String(data);
                case BUFFER_OVERFLOW:
                    System.out.println("receiveEncryptedData - while: case BUFFER_OVERFLOW");
                    // Adjust the buffer size if there's insufficient space
                    peerAppData = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());
                    break;
                case BUFFER_UNDERFLOW:
                    System.out.println("receiveEncryptedData - while: case BUFFER_UNDERFLOW");
                    // More data needed to be received from the peer
                    continue;
                case CLOSED:
                    System.out.println("receiveEncryptedData - while: case CLOSED");
                    return null; // SSL Engine has closed
            }
        }

        return null;
    }


}

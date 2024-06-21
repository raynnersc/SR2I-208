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

public class SSLHandler {
    private static SSLEngine sslEngine;

    public static void initSSLEngine(BluetoothSocket socket, Context context) throws IOException {
        try {
            SSLContext sslContext = createSSLContext(context);  // Method to initialize SSLContext with your certificates
            assert sslContext != null;
            sslEngine = sslContext.createSSLEngine();
            sslEngine.setUseClientMode(true);

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

    private static SSLContext createSSLContext(Context context) throws Exception {
        // Your implementation here to load client certificates and initialize SSLContext
        return null;
    }

}

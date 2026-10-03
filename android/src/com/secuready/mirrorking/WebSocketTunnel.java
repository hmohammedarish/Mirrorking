package com.secuready.mirrorking;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class WebSocketTunnel {
    private final Socket mSocket;
    private final InputStream mIn;
    private final OutputStream mOut;
    private final SecureRandom mRandom = new SecureRandom();

    public WebSocketTunnel(String host, int port, String path, boolean useSsl) throws Exception {
        if (useSsl) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(host, port);
            sslSocket.startHandshake();
            mSocket = sslSocket;
        } else {
            mSocket = new Socket(host, port);
        }
        mSocket.setTcpNoDelay(true);
        mIn = new BufferedInputStream(mSocket.getInputStream(), 128 * 1024);
        mOut = new BufferedOutputStream(mSocket.getOutputStream(), 128 * 1024);

        // Send Handshake
        String key = "dGhlIHNhbXBsZSBub25jZQ==";
        String req = "GET " + path + " HTTP/1.1\r\n" +
                "Host: " + host + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
        mOut.write(req.getBytes("UTF-8"));
        mOut.flush();

        // Read Handshake response headers
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = mIn.read()) != -1) {
            sb.append((char) b);
            if (sb.toString().endsWith("\r\n\r\n")) break;
        }
        if (!sb.toString().contains("101 Switching Protocols")) {
            throw new Exception("Handshake failed: " + sb.toString());
        }
    }

    public synchronized void sendBinaryFrame(byte[] payload, int offset, int length) throws Exception {
        // RFC 6455 Client-to-server frame MUST be masked
        byte[] mask = new byte[4];
        mRandom.nextBytes(mask);

        // Header: Fin=1, Opcode=2 (binary)
        mOut.write(0x82);

        // Length & Mask bit (0x80)
        if (length <= 125) {
            mOut.write(0x80 | length);
        } else if (length <= 65535) {
            mOut.write(0x80 | 126);
            mOut.write((length >> 8) & 0xFF);
            mOut.write(length & 0xFF);
        } else {
            mOut.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                mOut.write((int) (((long) length >> (8 * i)) & 0xFF));
            }
        }

        // Masking key
        mOut.write(mask);

        // Masked payload
        byte[] masked = new byte[length];
        for (int i = 0; i < length; i++) {
            masked[i] = (byte) (payload[offset + i] ^ mask[i % 4]);
        }
        mOut.write(masked);
        mOut.flush();
    }

    public byte[] readBinaryFrame() throws Exception {
        int b0 = mIn.read();
        if (b0 == -1) return null;
        int b1 = mIn.read();
        if (b1 == -1) return null;

        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;

        if (len == 126) {
            len = ((mIn.read() & 0xFF) << 8) | (mIn.read() & 0xFF);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | (mIn.read() & 0xFF);
            }
        }

        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(mask);
        }

        byte[] data = new byte[(int) len];
        readFully(data);

        if (masked && mask != null) {
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (data[i] ^ mask[i % 4]);
            }
        }

        // Handle ping (0x09) -> reply pong (0x0A)
        if (opcode == 0x09) {
            sendPong(data);
            return readBinaryFrame();
        }
        // Connection close (0x08)
        if (opcode == 0x08) {
            close();
            return null;
        }

        return data;
    }

    private void sendPong(byte[] payload) {
        try {
            mOut.write(0x8A);
            mOut.write(0x80 | (payload.length & 0x7F));
            byte[] mask = new byte[4];
            mRandom.nextBytes(mask);
            mOut.write(mask);
            for (int i = 0; i < payload.length; i++) {
                mOut.write(payload[i] ^ mask[i % 4]);
            }
            mOut.flush();
        } catch (Exception ignored) {}
    }

    private void readFully(byte[] b) throws Exception {
        int offset = 0;
        while (offset < b.length) {
            int read = mIn.read(b, offset, b.length - offset);
            if (read == -1) throw new Exception("Premature EOF");
            offset += read;
        }
    }

    public void close() {
        try { mSocket.close(); } catch (Exception ignored) {}
    }
}

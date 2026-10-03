package com.secuready.mirrorking;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class MirrorKingService extends Service {
    private static final String TAG = "MirrorKingService";
    private static final String CHANNEL_ID = "mirrorking_channel";
    private static final int NOTIF_ID = 1001;

    public static volatile MediaProjection sMediaProjection;
    public static volatile MirrorKingService sInstance;

    private PowerManager.WakeLock mWakeLock;
    private MediaCodec mCodec;
    private VirtualDisplay mVirtualDisplay;
    private Surface mInputSurface;

    private int mWidth = 720;
    private int mHeight = 1560;
    private int mDpi = 320;

    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final AtomicBoolean mEncoderRunning = new AtomicBoolean(false);
    private Thread mNetworkThread;
    private Socket mSocket;
    private DataOutputStream mOut;
    private DataInputStream mIn;
    private final Object mSocketLock = new Object();
    private WebSocketTunnel mWsTunnel;

    // ADB Tunnel to local adbd:5555
    private Socket mAdbSocket;
    private OutputStream mAdbOut;
    private Thread mAdbThread;
    private final Object mAdbLock = new Object();

    private volatile byte[] mSpsPpsData = null;

    private String mTargetHost = "127.0.0.1";
    private int mTargetPort = Config.DEFAULT_PORT;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        startForegroundNotification();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MirrorKing::WakeLock");
            mWakeLock.acquire(24 * 60 * 60 * 1000L);
        }

        readScreenDimensions();
        loadConfig();
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "MirrorKing Background Service",
                    NotificationManager.IMPORTANCE_MIN
            );
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build();

        startForeground(NOTIF_ID, notification);
    }

    private void readScreenDimensions() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int origW = dm.widthPixels;
            int origH = dm.heightPixels;
            mDpi = dm.densityDpi;

            float ratio = (float) origH / (float) origW;
            mWidth = 720;
            mHeight = (int) (mWidth * ratio);
            if (mWidth % 2 != 0) mWidth--;
            if (mHeight % 2 != 0) mHeight--;
            Log.i(TAG, "Screen resolution configured: " + mWidth + "x" + mHeight + " (orig: " + origW + "x" + origH + ")");
        }
    }

    private void loadConfig() {
        SharedPreferences sp = getSharedPreferences("mirrorking_prefs", MODE_PRIVATE);
        mTargetHost = sp.getString("host", "127.0.0.1");
        mTargetPort = sp.getInt("port", Config.DEFAULT_PORT);
    }

    public synchronized void onMediaProjectionAcquired(MediaProjection projection) {
        sMediaProjection = projection;
        Log.i(TAG, "MediaProjection attached to MirrorKingService");
        startEncoderAndStreaming();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String host = intent.getStringExtra("host");
            int port = intent.getIntExtra("port", 0);
            if (host != null && !host.isEmpty()) {
                mTargetHost = host;
            }
            if (port > 0) {
                mTargetPort = port;
            }
        }

        if (sMediaProjection != null && !mEncoderRunning.get()) {
            startEncoderAndStreaming();
        }

        if (!mRunning.get()) {
            mRunning.set(true);
            mNetworkThread = new Thread(new NetRunner(this), "MirrorKing-NetLoop");
            mNetworkThread.start();
        }
        return START_STICKY;
    }

    private static class NetRunner implements Runnable {
        private final MirrorKingService service;
        public NetRunner(MirrorKingService service) {
            this.service = service;
        }
        @Override
        public void run() {
            if (service != null) service.connectionLoop();
        }
    }

    private static class EncoderRunner implements Runnable {
        private final MirrorKingService service;
        public EncoderRunner(MirrorKingService service) {
            this.service = service;
        }
        @Override
        public void run() {
            if (service != null) service.encoderLoop();
        }
    }

    private static class AdbBridgeRunner implements Runnable {
        private final MirrorKingService service;
        public AdbBridgeRunner(MirrorKingService service) {
            this.service = service;
        }
        @Override
        public void run() {
            if (service != null) service.adbBridgeLoop();
        }
    }

    private static class MediaProjectionCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            super.onStop();
            Log.i(TAG, "MediaProjection stopped by system");
        }
    }

    private synchronized void startEncoderAndStreaming() {
        if (mEncoderRunning.get() || sMediaProjection == null) {
            return;
        }

        try {
            Log.i(TAG, "Initializing hardware MediaCodec encoder (" + mWidth + "x" + mHeight + ")...");
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mWidth, mHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, Config.DEFAULT_BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, Config.DEFAULT_FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);
            }

            mCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mInputSurface = mCodec.createInputSurface();
            mCodec.start();

            sMediaProjection.registerCallback(new MediaProjectionCallback(), mMainHandler);
            mVirtualDisplay = sMediaProjection.createVirtualDisplay(
                    "MirrorKingVD",
                    mWidth, mHeight, mDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mInputSurface, null, null
            );

            mEncoderRunning.set(true);
            new Thread(new EncoderRunner(this), "MirrorKing-Encoder").start();
            Log.i(TAG, "H.264 Hardware Encoder and VirtualDisplay started permanently!");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start encoder: " + e.getMessage(), e);
        }
    }

    private void connectionLoop() {
        while (mRunning.get()) {
            boolean isWs = mTargetHost != null && (mTargetHost.contains(".onrender.com") || mTargetHost.startsWith("ws://") || mTargetHost.startsWith("wss://"));
            try {
                if (isWs) {
                    String cleanHost = mTargetHost.replace("wss://", "").replace("ws://", "").replace("https://", "").replace("http://", "");
                    if (cleanHost.endsWith("/")) cleanHost = cleanHost.substring(0, cleanHost.length() - 1);
                    int port = (mTargetPort == Config.DEFAULT_PORT || mTargetPort == 0) ? 443 : mTargetPort;
                    Log.i(TAG, "Connecting to Render WebSocket Relay: wss://" + cleanHost + ":" + port + "/phone ...");
                    WebSocketTunnel tunnel = new WebSocketTunnel(cleanHost, port, "/phone", true);
                    synchronized (mSocketLock) {
                        mWsTunnel = tunnel;
                    }
                    Log.i(TAG, "Connected successfully to Render Relay via WSS!");

                    sendDeviceInfo();
                    if (mSpsPpsData != null) {
                        sendPacket(Config.PKT_VIDEO_CONFIG, mSpsPpsData, 0, (byte) 0);
                    }
                    requestKeyFrame();
                    startAdbTunnel();
                    readWsCommandLoop();

                } else {
                    Log.i(TAG, "Connecting to PC at " + mTargetHost + ":" + mTargetPort + "...");
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(mTargetHost, mTargetPort), 3000);
                    socket.setTcpNoDelay(true);

                    synchronized (mSocketLock) {
                        mSocket = socket;
                        mOut = new DataOutputStream(new BufferedOutputStream(mSocket.getOutputStream(), 128 * 1024));
                        mIn = new DataInputStream(mSocket.getInputStream());
                    }

                    Log.i(TAG, "Connected successfully to PC!");

                    sendDeviceInfo();
                    if (mSpsPpsData != null) {
                        sendPacket(Config.PKT_VIDEO_CONFIG, mSpsPpsData, 0, (byte) 0);
                    }
                    requestKeyFrame();
                    startAdbTunnel();
                    readCommandLoop();
                }

            } catch (Exception e) {
                Log.w(TAG, "Connection loop: " + e.getMessage() + ". Retrying in 2 seconds...");
            } finally {
                stopAdbTunnel();
                synchronized (mSocketLock) {
                    if (mWsTunnel != null) {
                        mWsTunnel.close();
                        mWsTunnel = null;
                    }
                    try {
                        if (mSocket != null) mSocket.close();
                    } catch (Exception ignored) {}
                    mSocket = null;
                    mOut = null;
                    mIn = null;
                }
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {}
        }
    }

    private void startAdbTunnel() {
        stopAdbTunnel();
        mAdbThread = new Thread(new AdbBridgeRunner(this), "MirrorKing-AdbBridge");
        mAdbThread.start();
    }

    private void stopAdbTunnel() {
        synchronized (mAdbLock) {
            try {
                if (mAdbSocket != null) mAdbSocket.close();
            } catch (Exception ignored) {}
            mAdbSocket = null;
            mAdbOut = null;
        }
        if (mAdbThread != null) {
            mAdbThread.interrupt();
            mAdbThread = null;
        }
    }

    private void adbBridgeLoop() {
        try {
            Socket adbSock = new Socket();
            adbSock.connect(new InetSocketAddress("127.0.0.1", 5555), 2000);
            adbSock.setTcpNoDelay(true);

            synchronized (mAdbLock) {
                mAdbSocket = adbSock;
                mAdbOut = adbSock.getOutputStream();
            }

            Log.i(TAG, "[AdbBridge] Connected to local adbd:5555!");
            InputStream adbIn = adbSock.getInputStream();
            byte[] buf = new byte[16384];
            int len;

            while (mRunning.get() && (len = adbIn.read(buf)) != -1) {
                byte[] chunk = new byte[len];
                System.arraycopy(buf, 0, chunk, 0, len);
                sendPacket(Config.PKT_ADB_DATA, chunk, 0, (byte) 0);
            }
        } catch (Exception e) {
            Log.i(TAG, "[AdbBridge] Idle/Closed: " + e.getMessage());
        } finally {
            synchronized (mAdbLock) {
                try {
                    if (mAdbSocket != null) mAdbSocket.close();
                } catch (Exception ignored) {}
                mAdbSocket = null;
                mAdbOut = null;
            }
        }
    }

    private void requestKeyFrame() {
        if (mCodec != null && mEncoderRunning.get()) {
            try {
                Bundle syncBundle = new Bundle();
                syncBundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                mCodec.setParameters(syncBundle);
                Log.i(TAG, "Requested sync keyframe from MediaCodec");
            } catch (Exception e) {
                Log.w(TAG, "Could not request sync frame: " + e.getMessage());
            }
        }
    }

    private void sendDeviceInfo() throws IOException {
        synchronized (mSocketLock) {
            if (mOut == null) return;
            mOut.write(Config.MAGIC);
            mOut.writeByte(Config.PKT_DEVICE_INFO);
            mOut.writeInt(8);
            mOut.writeLong(0);
            mOut.writeByte(0);
            mOut.writeInt(mWidth);
            mOut.writeInt(mHeight);
            mOut.flush();
        }
    }

    private void encoderLoop() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long frameCounter = 0;

        while (mRunning.get() && mEncoderRunning.get() && mCodec != null) {
            try {
                int outIndex = mCodec.dequeueOutputBuffer(bufferInfo, 20_000);
                if (outIndex >= 0) {
                    ByteBuffer outBuffer = mCodec.getOutputBuffer(outIndex);
                    if (outBuffer != null) {
                        outBuffer.position(bufferInfo.offset);
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size);

                        byte[] data = new byte[bufferInfo.size];
                        outBuffer.get(data);

                        boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                        if (isConfig) {
                            mSpsPpsData = data;
                            Log.i(TAG, "Captured SPS/PPS Config: " + data.length + " bytes");
                            sendPacket(Config.PKT_VIDEO_CONFIG, data, bufferInfo.presentationTimeUs, (byte) 0);
                        } else {
                            byte flags = isKeyFrame ? (byte) 1 : (byte) 0;
                            sendPacket(Config.PKT_VIDEO_FRAME, data, bufferInfo.presentationTimeUs, flags);
                            frameCounter++;
                            if (frameCounter % 120 == 0) {
                                Log.i(TAG, "Streaming active: " + frameCounter + " frames produced");
                            }
                        }
                    }
                    mCodec.releaseOutputBuffer(outIndex, false);
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = mCodec.getOutputFormat();
                    ByteBuffer csd0 = newFormat.getByteBuffer("csd-0");
                    ByteBuffer csd1 = newFormat.getByteBuffer("csd-1");
                    if (csd0 != null && csd1 != null) {
                        byte[] sps = new byte[csd0.remaining()];
                        csd0.get(sps);
                        byte[] pps = new byte[csd1.remaining()];
                        csd1.get(pps);
                        byte[] combined = new byte[sps.length + pps.length];
                        System.arraycopy(sps, 0, combined, 0, sps.length);
                        System.arraycopy(pps, 0, combined, sps.length, pps.length);
                        mSpsPpsData = combined;
                        Log.i(TAG, "Captured SPS/PPS from INFO_OUTPUT_FORMAT_CHANGED: " + combined.length + " bytes");
                        sendPacket(Config.PKT_VIDEO_CONFIG, combined, 0, (byte) 0);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Encoder loop exception: " + e.getMessage());
                break;
            }
        }
        mEncoderRunning.set(false);
    }

    private void sendPacket(byte type, byte[] payload, long pts, byte flags) {
        synchronized (mSocketLock) {
            if (mWsTunnel != null) {
                try {
                    byte[] pkt = new byte[18 + payload.length];
                    System.arraycopy(Config.MAGIC, 0, pkt, 0, 4);
                    pkt[4] = type;
                    pkt[5] = (byte) ((payload.length >> 24) & 0xFF);
                    pkt[6] = (byte) ((payload.length >> 16) & 0xFF);
                    pkt[7] = (byte) ((payload.length >> 8) & 0xFF);
                    pkt[8] = (byte) (payload.length & 0xFF);
                    for (int i = 0; i < 8; i++) {
                        pkt[9 + i] = (byte) ((pts >> ((7 - i) * 8)) & 0xFF);
                    }
                    pkt[17] = flags;
                    System.arraycopy(payload, 0, pkt, 18, payload.length);
                    mWsTunnel.sendBinaryFrame(pkt, 0, pkt.length);
                } catch (Exception e) {
                    Log.w(TAG, "WS write failed: " + e.getMessage());
                }
                return;
            }

            if (mOut == null) return;
            try {
                mOut.write(Config.MAGIC);
                mOut.writeByte(type);
                mOut.writeInt(payload.length);
                mOut.writeLong(pts);
                mOut.writeByte(flags);
                mOut.write(payload);
                mOut.flush();
            } catch (IOException e) {
                Log.w(TAG, "Socket write failed: " + e.getMessage());
            }
        }
    }

    private void readWsCommandLoop() throws Exception {
        while (mRunning.get() && mWsTunnel != null) {
            byte[] frame = mWsTunnel.readBinaryFrame();
            if (frame == null || frame.length < 18) continue;
            if (frame[0] != Config.MAGIC[0] || frame[1] != Config.MAGIC[1] ||
                frame[2] != Config.MAGIC[2] || frame[3] != Config.MAGIC[3]) {
                continue;
            }
            byte cmdType = frame[4];
            int len = ((frame[5] & 0xFF) << 24) | ((frame[6] & 0xFF) << 16) |
                      ((frame[7] & 0xFF) << 8) | (frame[8] & 0xFF);
            if (frame.length >= 18 + len) {
                byte[] payload = new byte[len];
                System.arraycopy(frame, 18, payload, 0, len);
                handleCommand(cmdType, payload);
            }
        }
    }

    private void readCommandLoop() throws IOException {
        byte[] magicBuf = new byte[4];
        while (mRunning.get()) {
            DataInputStream in;
            synchronized (mSocketLock) {
                in = mIn;
            }
            if (in == null) break;

            in.readFully(magicBuf);
            if (magicBuf[0] != Config.MAGIC[0] || magicBuf[1] != Config.MAGIC[1] ||
                magicBuf[2] != Config.MAGIC[2] || magicBuf[3] != Config.MAGIC[3]) {
                continue;
            }

            byte cmdType = in.readByte();
            int len = in.readInt();
            byte[] payload = new byte[len];
            if (len > 0) {
                in.readFully(payload);
            }

            handleCommand(cmdType, payload);
        }
    }

    private void handleCommand(byte cmdType, byte[] payload) {
        if (cmdType == Config.CMD_ADB_DATA) {
            // Forward raw ADB stream from PC to phone's local adbd (5555)
            synchronized (mAdbLock) {
                if (mAdbOut != null) {
                    try {
                        mAdbOut.write(payload);
                        mAdbOut.flush();
                    } catch (IOException e) {
                        Log.w(TAG, "Error writing to local adbd: " + e.getMessage());
                    }
                }
            }
            return;
        }

        MirrorKingAccessibilityService a11y = MirrorKingAccessibilityService.instance;

        if (cmdType == Config.CMD_TOUCH && payload.length >= 9) {
            int action = payload[0] & 0xFF;
            int xInt = ((payload[1] & 0xFF) << 24) | ((payload[2] & 0xFF) << 16) |
                       ((payload[3] & 0xFF) << 8) | (payload[4] & 0xFF);
            int yInt = ((payload[5] & 0xFF) << 24) | ((payload[6] & 0xFF) << 16) |
                       ((payload[7] & 0xFF) << 8) | (payload[8] & 0xFF);
            float normX = Float.intBitsToFloat(xInt);
            float normY = Float.intBitsToFloat(yInt);

            DisplayMetrics dm = getResources().getDisplayMetrics();
            float realX = normX * dm.widthPixels;
            float realY = normY * dm.heightPixels;

            if (a11y != null) {
                a11y.injectTouch(action, realX, realY);
            }
        } else if (cmdType == Config.CMD_GLOBAL_ACT && payload.length >= 1) {
            int act = payload[0] & 0xFF;
            if (a11y != null) {
                a11y.performGlobal(act);
            }
        } else if (cmdType == Config.CMD_UNLOCK_PIN) {
            String pin = new String(payload);
            if (a11y != null) {
                a11y.unlockDevice(pin);
            }
        } else if (cmdType == Config.CMD_TEXT_INPUT) {
            String text = new String(payload);
            if (a11y != null) {
                a11y.enterPinOrText(text);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mRunning.set(false);
        mEncoderRunning.set(false);
        stopAdbTunnel();
        synchronized (mSocketLock) {
            try {
                if (mSocket != null) mSocket.close();
            } catch (Exception ignored) {}
            mSocket = null;
            mOut = null;
            mIn = null;
        }
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        sInstance = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

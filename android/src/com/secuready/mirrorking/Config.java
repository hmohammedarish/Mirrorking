package com.secuready.mirrorking;

public class Config {
    // Magic header for framing
    public static final byte[] MAGIC = new byte[]{(byte) 0x4D, (byte) 0x49, (byte) 0x52, (byte) 0x52}; // "MIRR"

    // Packet types (Phone -> PC)
    public static final byte PKT_VIDEO_CONFIG = 0x01; // SPS / PPS
    public static final byte PKT_VIDEO_FRAME  = 0x02; // H.264 NAL / Frame
    public static final byte PKT_DEVICE_INFO  = 0x03; // Screen dimensions
    public static final byte PKT_HEARTBEAT    = 0x04; // Keep-alive ping
    public static final byte PKT_ADB_DATA     = 0x20; // Raw ADB data from phone adbd (5555) -> PC

    // Command types (PC -> Phone)
    public static final byte CMD_TOUCH        = 0x10; // Action + X + Y
    public static final byte CMD_GLOBAL_ACT   = 0x11; // Back, Home, Recents, Power
    public static final byte CMD_TEXT_INPUT   = 0x12; // Text injection
    public static final byte CMD_UNLOCK_PIN   = 0x13; // Wake + Swipe + Type PIN
    public static final byte CMD_KEY_EVENT    = 0x14; // Android KeyCode
    public static final byte CMD_ADB_DATA     = 0x20; // Raw ADB data from PC -> phone adbd (5555)

    // Touch actions
    public static final byte TOUCH_DOWN       = 0x00;
    public static final byte TOUCH_MOVE       = 0x01;
    public static final byte TOUCH_UP         = 0x02;

    // Global actions
    public static final byte ACT_BACK         = 0x01;
    public static final byte ACT_HOME         = 0x02;
    public static final byte ACT_RECENTS      = 0x03;
    public static final byte ACT_POWER        = 0x04;
    public static final byte ACT_NOTIF        = 0x05;
    public static final byte ACT_QUICK_SET    = 0x06;

    // Network defaults
    public static final int DEFAULT_PORT = 8888;
    public static final int DEFAULT_BITRATE = 4_000_000;
    public static final int DEFAULT_FPS = 60;
}

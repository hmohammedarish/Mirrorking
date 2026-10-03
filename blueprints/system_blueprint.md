# 👑 MirrorKing System Blueprint: Scrcpy Over Mobile Data & Zero-Alert Mirroring

## 1. Problem Statement & Root Cause Analysis

### The Snapchat / "Screen Recording" Detection Issue
* **MediaProjection API (Android OS)**:
  * Any standard Android application capturing the screen must request a `MediaProjection` token from the user.
  * When active, Android 14, 15, and 16 invoke `WindowManager.addScreenRecordingCallback()`, notifying applications that a screen recording/casting session is active.
  * Apps like Snapchat, banking apps, and secure messengers hook into this callback and immediately flag the activity, alerting users (e.g., *"Username is screen recording!"*).
* **The Scrcpy Advantage (SurfaceControl)**:
  * `scrcpy` runs under the system `shell` UID (2000) using `app_process`.
  * It communicates directly with Android's display compositor (`SurfaceFlinger` / `SurfaceControl`).
  * It does **not** create a `MediaProjection` session.
  * As a result, the operating system treats it as the physical device display. Snapchat, Instagram, and banking apps see it as normal hardware display output—**Zero Alerts**.

---

## 2. The Solution Architecture

To surpass scrcpy and solve the cracked-screen dilemma without triggering screen recording alerts:

```
┌────────────────────────────────────────────────────────┐
│               ANDROID DEVICE (Cracked Screen)          │
│                                                        │
│  1. SurfaceControl / app_process (scrcpy core)         │
│     - Hardware display mirror (0 alerts on Snapchat)   │
│     - Raw kernel MotionEvents (pixel-perfect touch)    │
│                                                        │
│  2. adbd Daemon (tcp:5555)                             │
│     - Listens internally on localhost:5555             │
│                                                        │
│  3. MirrorKing Guardian APK (Persistent Service)       │
│     - Auto-starts on boot (BOOT_COMPLETED)             │
│     - Makes outbound connection over 4G/5G mobile data │
│     - Pipes internal tcp:5555 over outbound tunnel     │
│     - Pre-authorized Accessibility Service lifeline    │
└───────────────────────────┬────────────────────────────┘
                            │
              Outbound Encrypted Tunnel
              (Bypasses Mobile Carrier CGNAT)
                            │
┌───────────────────────────▼────────────────────────────┐
│                    WINDOWS 11 PC                       │
│                                                        │
│  1. MirrorKing Tunnel Hub                              │
│     - Receives phone's outbound tunnel connection      │
│     - Exposes local virtual ADB port: 127.0.0.1:7777   │
│                                                        │
│  2. Native Scrcpy Engine                               │
│     - Connects directly to 127.0.0.1:7777              │
│     - Full hardware GPU decoding (VAAPI / DXVA2)       │
│     - Native keyboard, mouse scroll, gestures          │
│     - Audio streaming with zero latency                │
└────────────────────────────────────────────────────────┘
```

---

## 3. How the Mobile Data Tunneling Works

1. **Carrier NAT Problem**: Cellular carriers (4G/5G) place phones behind Carrier-Grade NAT (CGNAT), blocking all incoming connection attempts.
2. **Reverse Pipe Solution**:
   * The Android Guardian APK reaches **outbound** to your PC's IP or Cloud Relay on port `8888`.
   * Since the connection was initiated from inside the phone out to the internet, carrier firewalls permit the two-way traffic.
   * When PC ADB connects to `127.0.0.1:7777`, the PC Bridge forwards the raw ADB packets through the outbound tunnel straight to the phone's internal `adbd` (port `5555`).
   * Scrcpy connects through this forwarded port seamlessly, streaming native `SurfaceControl` video and receiving raw `MotionEvent` touches from anywhere in the world.

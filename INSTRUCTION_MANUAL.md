# MirrorKing — The Definitive Instruction Book & Operations Manual
**Universal Non-Rooted Android Remote Mirroring & Stealth Control System**  
*Optimized for Samsung Galaxy S22 (`SM-S901E`, Android 16) with Shattered/Unusable Display*

---

## 1. System Philosophy & Architecture

MirrorKing is an ultra-lightweight, zero-alert screen mirroring and remote control system built specifically to rescue and operate modern Android phones with non-functional or completely shattered screens, without requiring root access or high-end PC hardware.

```
       +-----------------------------------------------------------+
       |                  SAMSUNG GALAXY S22                       |
       |  [Broken Screen]                                          |
       |  - Guardian Companion APK (com.secuready.mirrorking)      |
       |  - Persistent Accessibility Engine                        |
       |  - Native Compositor Video Pipe (SurfaceControl)          |
       +-----------------------------+-----------------------------+
                                     |
               [OUTBOUND REVERSE TUNNEL (PORT 8888)]
            (USB Cable / Local Wi-Fi / 4G-5G Cellular)
                                     |
                                     v
       +-----------------------------+-----------------------------+
       |                     WINDOWS 11 PC                         |
       |  - Local Virtual Bridge: 127.0.0.1:7777 (Node.js)         |
       |  - Hardware Renderer: Direct3D 11 (scrcpy)                |
       |  - RAM Consumption: < 50 MB | CPU Usage: < 2%             |
       +-----------------------------------------------------------+
```

### Why MirrorKing Triggers ZERO Screen Recording Alerts
Standard Android mirroring tools rely on the public `MediaProjection` API. When an app captures the screen via `MediaProjection`, Android automatically fires `WindowManager.addScreenRecordingCallback()`. Apps like **Snapchat, Telegram Secret Chats, and Banking Apps** immediately detect this flag and display:
> *"Snapchat screenshot / screen recording alert sent to recipient"*

MirrorKing circumvents this entirely:
1. It executes through the native Android compositor (`SurfaceControl`) under Android Shell UID `2000`.
2. The operating system treats this stream as a hardware display output (similar to connecting an external TV via HDMI/DisplayPort), **not** an application recording.
3. You can view Snaps, open banking apps, and navigate sensitive chats without sending recording warnings or getting blocked by DRM black screens.

---

## 2. Master Controls & Keybindings Cheat Sheet

You control your phone entirely with your PC's physical keyboard and mouse:

### Basic Mouse Gestures
| PC Action | Android Phone Equivalent |
| :--- | :--- |
| **Left Mouse Click** | Tap / Touch screen |
| **Right Mouse Click** | **BACK Button** |
| **Middle Mouse Click** | **HOME Button** |
| **Click + Drag (Up/Down/Left/Right)** | Swipe / Flick / Scroll gesture |
| **Mouse Scroll Wheel** | Vertical page scrolling |
| **Click + Hold (1 second)** | Long press (opens context menus) |

---

### Hardware Navigation & Shortcuts (Hold `Alt` or `Cmd`)
| Shortcut | Action Description |
| :--- | :--- |
| **`Alt` + `H`** | Go to **Home Screen** |
| **`Alt` + `B`** (or Right-Click) | Trigger **Back** |
| **`Alt` + `S`** | Open **Recent Apps / App Switcher** |
| **`Alt` + `P`** | Press **Power Button** (Turns display on / off) |
| **`Alt` + `O`** *(Essential!)* | **Stealth Mode / Screen Off**: Turns off the phone's physical broken screen while the PC mirror stays 100% active. Keeps the phone cool and saves battery. |
| **`Alt` + `N`** | Pull down **Notification Shade** |
| **`Alt` + `Shift` + `N`** | Pull down **Quick Settings** panel |
| **`Alt` + `F`** | Toggle **Fullscreen Mode** |
| **`Alt` + `Up` / `Down`** | **Volume Up / Volume Down** |
| **`Alt` + `Shift` + `P`** | Open Android **Power Menu** (Restart / Power Off) |

---

### Text Input & Clipboard Sync
| Action | Description |
| :--- | :--- |
| **Typing on Keyboard** | Characters are typed directly into the focused Android text box. |
| **Ctrl + C / Ctrl + V** | **Unified Clipboard Sync**: Copy text on your PC and paste it instantly on your phone with `Ctrl + V` (and vice-versa). |
| **Enter Key** | Sends message or submits forms. |
| **Backspace / Delete** | Deletes characters normally. |

---

## 3. How the 4G / 5G Cellular Remote Access Works

```
   [GALAXY S22]                                    [YOUR PC]
 (On 4G/5G Cellular)                            (At Home/Office)
          |                                             |
   Punches OUTWARD                               Listens for Tunnel
          |                                             |
          +-----------> [CLOUDFLARE / RELAY] <----------+
                                |
                  Full Bi-Directional Bridge
                  (Zero CGNAT blocking issues)
```

### The Telecom Problem: Carrier-Grade NAT (CGNAT)
When your phone is on Mobile Data (Jio, Airtel, Vi, AT&T, T-Mobile, Vodafone, etc.), telecom towers assign your phone a private cellular IP address (e.g. `10.x.x.x` or `100.64.x.x`). 
* This means **your PC cannot directly "call" or connect to the phone's cellular IP address**. The telecom firewall drops all incoming connection attempts.
* This is why standard wireless ADB or regular network tools permanently fail the moment you unplug from Wi-Fi.

### The MirrorKing Solution: Reverse Outbound Punching
MirrorKing overcomes CGNAT by reversing the direction of the initial connection:
1. **The Phone Initiates Outward**: Cellular carriers permit all outbound connections. The resident **Guardian APK** (`com.secuready.mirrorking`) establishes an encrypted outbound TCP connection to a known public address (your PC's public URL or relay).
2. **Local Loopback Bridge**: Inside the phone, the Guardian APK bridges this connection directly to the phone's internal `adbd` daemon on `127.0.0.1:5555`.
3. **PC Virtual Bridge**: On your computer, `pc\server.js` listens on `127.0.0.1:7777`. When `scrcpy` connects to `127.0.0.1:7777`, packets are piped instantly across the cellular tunnel.
4. **Result**: Your PC controls the phone with full 60 FPS hardware video and kernel touch events, even if your phone is in another city on 5G.

---

### Step-by-Step Setup: Cellular WAN Traversal

You can use any of the three traversal options depending on your preference:

#### Option A: Zero-Config Free Tunnel (Recommended — No Router Setup)
Use a free instant tunnel service like **Pinggy** or **Cloudflare Tunnel** on your PC:
1. On your PC, open a terminal and run:
   ```cmd
   ssh -p 443 -R0:localhost:8888 a.pinggy.io
   ```
   *(Or download the single 15MB `cloudflared.exe` from Cloudflare).*
2. Pinggy immediately gives you a public address, e.g.:
   `tcp://t-xyz123.pinggy.link:49152`
3. Point your phone to that host and port:
   ```cmd
   adb shell am start -n com.secuready.mirrorking/.MainActivity --es host "t-xyz123.pinggy.link" --ei port 49152
   ```
4. Double-click the **`MirrorKing`** shortcut on your Desktop. You are now mirroring over cellular data!

#### Option B: Deploy the Free MirrorKing Cloud Relay (`relay/server.js`)
If you want a standing 24/7 endpoint:
1. The project includes a dedicated cloud relay: [`relay/server.js`](file:///E:/SECUREADY/Mirrorking/relay/server.js).
2. You can deploy it to any free cloud host (such as Render, Fly.io, or Railway) in 2 minutes.
3. Both your PC and your phone connect to your relay server. The relay binds them into a low-latency pipe.

#### Option C: Home Router Port Forwarding
If your PC stays connected to your home Wi-Fi:
1. Log into your home Wi-Fi router (usually `192.168.1.1`).
2. Forward **External Port 8888 (TCP)** to your PC's local IP address (e.g. `192.168.1.50`, Port `8888`).
3. Set your phone to connect to your public home IP (or free No-IP DDNS hostname).

---

## 4. Quick-Start Modes

### Mode 1: High-Speed USB Mode (Default)
1. Plug the phone into your PC with a USB-C cable.
2. Double-click the **`MirrorKing`** desktop shortcut (or run [`launch-scrcpy.bat`](file:///E:/SECUREADY/Mirrorking/launch-scrcpy.bat)).
3. The interactive window will pop up immediately.

### Mode 2: Local Wi-Fi Mode (Same Room / House)
1. Find your PC's local Wi-Fi IP address (run `ipconfig` in CMD, e.g. `192.168.1.25`).
2. Send the connection target to the phone once:
   ```cmd
   adb shell am start -n com.secuready.mirrorking/.MainActivity --es host "192.168.1.25" --ei port 8888
   ```
3. Unplug the USB cable. Run `launch-scrcpy.bat`.

### Mode 3: Cellular Mobile Data Mode (Anywhere)
1. Set the public host/port as described in Section 3.
2. The phone connects through its 4G/5G connection.
3. Run `launch-scrcpy.bat`.

---

## 5. Auto-Recovery, Persistence & Broken Screen Quirks

Because the physical screen is shattered, MirrorKing includes failsafe mechanisms to prevent lockouts:

1. **Reboot Survival (`BOOT_COMPLETED`)**:
   * The Android manifest contains `android.permission.RECEIVE_BOOT_COMPLETED`.
   * If the phone runs out of battery, updates, or restarts, `BootReceiver.java` launches the background service automatically as soon as Android boots up.
2. **Battery Optimization Immunity**:
   * The app is registered in Android's Doze whitelist (`dumpsys deviceidle whitelist +com.secuready.mirrorking`). Android's task killer will never terminate the tunnel in the background.
3. **Cable Shake / Flapping Reconnect**:
   * [`launch-scrcpy.bat`](file:///E:/SECUREADY/Mirrorking/launch-scrcpy.bat) is wrapped in an infinite auto-healing reconnect loop. If the USB cable shifts or the wireless socket blips, it re-establishes the connection in 2 seconds without you needing to do anything.
4. **Emergency Browser Console (`http://localhost:3000`)**:
   * If you visit `http://localhost:3000` in Google Chrome or Microsoft Edge, you will see the **Stealth Rescue Console**.
   * **Why does the browser screen show Stealth Mode?** To guarantee 100% zero-alerts, zero status-bar "Phone is being monitored" banners, and zero Snapchat screen recording triggers, Android's `MediaProjection` API is kept strictly dormant.
   * **What can you do in the browser?** 
     - Enter PIN or password to unlock your phone even with a broken screen.
     - Type long text, paste URLs, or trigger navigation (Home, Back, Recents, Notifications).
     - Click **"🚀 Launch 60 FPS Native Mirror"** right from the web page to open the zero-alert Direct3D 11 window!

---

## 6. Project File Structure & Blueprints

All project components and architectural diagrams are stored in the local workspace:

* [`launch-scrcpy.bat`](file:///E:/SECUREADY/Mirrorking/launch-scrcpy.bat): Master auto-healing desktop launcher (60 FPS, Zero-Alerts).
* [`run-pc.bat`](file:///E:/SECUREADY/Mirrorking/run-pc.bat): Unified PC launcher (starts background server + launches mirror).
* [`bin/MirrorKing.apk`](file:///E:/SECUREADY/Mirrorking/bin/MirrorKing.apk): Ultra-lightweight (20.9 KB) resident guardian companion APK.
* [`pc/server.js`](file:///E:/SECUREADY/Mirrorking/pc/server.js): High-efficiency Node.js virtual bridge & web rescue engine.
* [`relay/server.js`](file:///E:/SECUREADY/Mirrorking/relay/server.js): Standalone cloud relay for cellular WAN routing.
* [`blueprints/browser_vs_scrcpy_blueprint.svg`](file:///E:/SECUREADY/Mirrorking/blueprints/browser_vs_scrcpy_blueprint.svg): Vector blueprint contrasting Browser vs Native Scrcpy engine.
* [`blueprints/architecture_blueprint.svg`](file:///E:/SECUREADY/Mirrorking/blueprints/architecture_blueprint.svg): Vector system architecture diagram.
* [`blueprints/cellular_tunnel_blueprint.svg`](file:///E:/SECUREADY/Mirrorking/blueprints/cellular_tunnel_blueprint.svg): Vector cellular NAT traversal blueprint.
* [`blueprints/render_relay_blueprint.svg`](file:///E:/SECUREADY/Mirrorking/blueprints/render_relay_blueprint.svg): Vector Render.com cloud relay deployment topology.
* [`blueprints/system_blueprint.md`](file:///E:/SECUREADY/Mirrorking/blueprints/system_blueprint.md): Deep technical architecture specification.

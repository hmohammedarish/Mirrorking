# 👑 MirrorKing - Universal Android Mirror & Remote

A high-performance, ultra-lightweight Android mirroring and remote-control system built specifically to solve the limitations of `scrcpy` and rescue devices with **cracked / unusable touch screens**.

---

## ⚡ Why MirrorKing is Better Than scrcpy

| Feature | Standard scrcpy | **MirrorKing** |
| :--- | :--- | :--- |
| **PC RAM / CPU Usage** | ~150 MB (FFmpeg + SDL2) | **< 50 MB** (Edge WebCodecs GPU decoding) |
| **Cracked-Screen Survival** | ❌ Locks you out if ADB disconnects | **✅ Permanent Accessibility Service lifeline** |
| **Survives Phone Reboots** | ❌ Dies; requires USB replug for `tcpip` | **✅ Auto-boots on startup (`BOOT_COMPLETED`)** |
| **Mobile Data (4G/5G)** | ❌ Fails (blocked by Carrier CGNAT) | **✅ Works over Cellular via outbound relay** |
| **Dynamic IP Changes** | ❌ Hardcoded IPs break when DHCP changes | **✅ Automatic connection negotiation** |
| **System Permission Dialogs** | ❌ Requires touching broken screen | **✅ Auto-accept bot clicks "Start now"** |
| **Emergency Rescue Panel** | ❌ None | **✅ Blind PIN Unlock & Direct Text Typing** |

---

## 🚀 Quick Start (1-Click Setup)

### Step 1: Initial Setup (Only Run Once)
1. Plug your phone into your PC via USB cable.
2. Double-click `setup-phone.bat`.
   * Automatically compiles the ultra-lightweight APK (~21 KB).
   * Installs it onto your phone.
   * Activates the Accessibility Service (remote touch & auto-accept).
   * Whitelists battery optimization so Samsung One UI never kills it.
3. **You can now unplug the USB cable!**

### Step 2: Launch Mirroring Anytime
* Double-click `run-pc.bat`.
* A hardware-accelerated desktop window opens automatically.
* Click, swipe, and type using your PC mouse and keyboard!

---

## 🌐 Connection Modes

### 1. USB Mode (0ms Latency)
* When plugged in via USB, `adb reverse` handles communication over the cable with zero latency.

### 2. Wi-Fi Mode (Local Network)
* When on the same Wi-Fi, the phone connects directly to your PC's IP port `8888`.

### 3. Mobile Data (Cellular 4G/5G)
* When away from home on mobile data, the phone connects outbound to the lightweight Cloud Relay (`relay/server.js`) or via a free tunnel (e.g. Cloudflare / Pinggy).

---

## 🛠 Project Structure

```
e:\SECUREADY\Mirrorking\
├── android\               # Ultra-lightweight Android companion app source
│   ├── src\               # Screen capture, MediaCodec, and Accessibility service
│   ├── res\               # Strings and accessibility config
│   └── AndroidManifest.xml
├── bin\                   # Compiled output (MirrorKing.apk - only 21 KB!)
├── pc\                    # Lightweight PC controller
│   ├── server.js          # TCP socket receiver & WebSocket broadcaster
│   └── public\            # WebCodecs hardware GPU player & Rescue UI
├── relay\                 # Cloud relay for 4G/5G mobile data traversal
├── build-apk.bat          # 1-Click compiler using standalone Android SDK
├── setup-phone.bat        # 1-Click phone provisioner and permission granter
└── run-pc.bat             # 1-Click desktop launcher
```

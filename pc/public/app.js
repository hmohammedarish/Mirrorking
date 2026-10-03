// MirrorKing WebCodecs Video & Controller Client

const canvas = document.getElementById('videoCanvas');
const ctx = canvas.getContext('2d');
const connectingOverlay = document.getElementById('connectingOverlay');
const statusBadge = document.getElementById('statusBadge');
const fpsMeter = document.getElementById('fpsMeter');
const bitrateMeter = document.getElementById('bitrateMeter');
const modeMeter = document.getElementById('modeMeter');

let ws = null;
let videoDecoder = null;
let deviceWidth = 720;
let deviceHeight = 1560;
let isDragging = false;
let isConnected = false;
let hasReceivedKeyframe = false;
let currentCodecString = 'avc1.42001f';

// Parse H.264 SPS to extract exact codec string (e.g. avc1.64002a or avc1.42e01f)
function extractAvcCodecFromSps(spsBytes) {
    for (let i = 0; i < spsBytes.length - 4; i++) {
        // Find NAL start code (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
        let nalOffset = -1;
        if (spsBytes[i] === 0 && spsBytes[i+1] === 0 && spsBytes[i+2] === 1) {
            nalOffset = i + 3;
        } else if (spsBytes[i] === 0 && spsBytes[i+1] === 0 && spsBytes[i+2] === 0 && spsBytes[i+3] === 1) {
            nalOffset = i + 4;
        }

        if (nalOffset !== -1 && nalOffset < spsBytes.length) {
            const nalType = spsBytes[nalOffset] & 0x1F;
            if (nalType === 7 && nalOffset + 3 < spsBytes.length) { // 7 = SPS
                const profile = spsBytes[nalOffset + 1].toString(16).padStart(2, '0');
                const compat  = spsBytes[nalOffset + 2].toString(16).padStart(2, '0');
                const level   = spsBytes[nalOffset + 3].toString(16).padStart(2, '0');
                return `avc1.${profile}${compat}${level}`;
            }
        }
    }
    return 'avc1.42001f';
}

function configureDecoder(codecStr) {
    if (!('VideoDecoder' in window)) {
        alert('WebCodecs VideoDecoder is not supported in this browser. Please use Microsoft Edge or Google Chrome.');
        return;
    }

    try {
        if (videoDecoder && videoDecoder.state !== 'closed') {
            videoDecoder.close();
        }

        videoDecoder = new VideoDecoder({
            output: (videoFrame) => {
                if (canvas.width !== videoFrame.displayWidth || canvas.height !== videoFrame.displayHeight) {
                    canvas.width = videoFrame.displayWidth;
                    canvas.height = videoFrame.displayHeight;
                }
                ctx.drawImage(videoFrame, 0, 0, canvas.width, canvas.height);
                videoFrame.close();
                connectingOverlay.classList.add('hidden');
            },
            error: (err) => {
                console.warn('[WebCodecs Warning]', err.message);
                hasReceivedKeyframe = false;
            }
        });

        currentCodecString = codecStr || currentCodecString;
        videoDecoder.configure({
            codec: currentCodecString,
            optimizeForLatency: true
        });
        hasReceivedKeyframe = false;
        console.log(`[WebCodecs] Configured with codec: ${currentCodecString}`);
    } catch (e) {
        console.error('[WebCodecs Config Error]', e);
    }
}

// Connect to local PC server WebSocket
function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}`;

    ws = new WebSocket(wsUrl);
    ws.binaryType = 'arraybuffer';

    ws.onopen = () => {
        console.log('[WS] Connected to MirrorKing PC Server');
    };

    ws.onmessage = (event) => {
        if (typeof event.data === 'string') {
            handleJsonMessage(JSON.parse(event.data));
        } else if (event.data instanceof ArrayBuffer) {
            handleBinaryVideoChunk(event.data);
        }
    };

    ws.onclose = () => {
        console.log('[WS] Server connection closed. Reconnecting in 2s...');
        setConnectionState(false);
        setTimeout(connectWebSocket, 2000);
    };

    ws.onerror = (err) => {
        console.error('[WS Error]', err);
    };
}

function handleJsonMessage(msg) {
    if (msg.type === 'status') {
        setConnectionState(msg.connected, msg.isUsb);
    } else if (msg.type === 'telemetry') {
        fpsMeter.textContent = msg.fps;
        bitrateMeter.textContent = msg.bitrateKbps;
        if (!msg.connected) {
            setConnectionState(false);
        }
    } else if (msg.type === 'device_info') {
        deviceWidth = msg.width;
        deviceHeight = msg.height;
        canvas.width = deviceWidth;
        canvas.height = deviceHeight;
        console.log(`[Device Info] ${deviceWidth}x${deviceHeight}`);
    } else if (msg.type === 'config' && msg.data) {
        const rawBytes = Uint8Array.from(atob(msg.data), c => c.charCodeAt(0));
        const detectedCodec = extractAvcCodecFromSps(rawBytes);
        configureDecoder(detectedCodec);
    }
}

function handleBinaryVideoChunk(buffer) {
    if (!videoDecoder || videoDecoder.state !== 'configured') {
        configureDecoder(currentCodecString);
    }

    const dataView = new DataView(buffer);
    const isKey = dataView.getUint8(0) === 1;
    const pts = Number(dataView.getBigInt64(1));
    const nalData = new Uint8Array(buffer, 9);

    if (isKey) {
        hasReceivedKeyframe = true;
    }

    // A keyframe is strictly required after configure() before decoding delta frames
    if (!hasReceivedKeyframe) {
        return;
    }

    try {
        videoDecoder.decode(new EncodedVideoChunk({
            type: isKey ? 'key' : 'delta',
            timestamp: pts,
            data: nalData
        }));
    } catch (e) {
        console.warn('[Decode Chunk Error]', e);
        hasReceivedKeyframe = false;
    }
}

function setConnectionState(connected, isUsb = false) {
    isConnected = connected;
    if (connected) {
        statusBadge.textContent = isUsb ? 'Connected (USB)' : 'Connected (Wireless)';
        statusBadge.className = 'badge connected';
        modeMeter.textContent = isUsb ? 'USB (0ms lag)' : 'Wireless';
    } else {
        statusBadge.textContent = 'Disconnected';
        statusBadge.className = 'badge';
        modeMeter.textContent = '-';
        connectingOverlay.classList.remove('hidden');
        hasReceivedKeyframe = false;
    }
}

// Mouse Touch & Gesture Injection
function getNormalizedCoords(e) {
    const rect = canvas.getBoundingClientRect();
    const x = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    const y = Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height));
    return { x, y };
}

canvas.addEventListener('mousedown', (e) => {
    if (!isConnected || e.button !== 0) return;
    isDragging = true;
    const { x, y } = getNormalizedCoords(e);
    sendTouchCommand(0, x, y); // 0 = TOUCH_DOWN
});

window.addEventListener('mousemove', (e) => {
    if (!isConnected || !isDragging) return;
    const { x, y } = getNormalizedCoords(e);
    sendTouchCommand(1, x, y); // 1 = TOUCH_MOVE
});

window.addEventListener('mouseup', (e) => {
    if (!isConnected || !isDragging) return;
    isDragging = false;
    const { x, y } = getNormalizedCoords(e);
    sendTouchCommand(2, x, y); // 2 = TOUCH_UP
});

// Right click for Android Back Button
canvas.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    if (isConnected) {
        sendGlobalCommand(1); // 1 = ACT_BACK
    }
});

// Mouse wheel for vertical scroll
canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    if (!isConnected) return;
    const { x, y } = getNormalizedCoords(e);
    const delta = e.deltaY;
    const scrollStep = delta > 0 ? 0.08 : -0.08;

    sendTouchCommand(0, x, y);
    sendTouchCommand(1, x, y - scrollStep);
    setTimeout(() => {
        sendTouchCommand(2, x, y - scrollStep);
    }, 40);
}, { passive: false });

function sendTouchCommand(action, x, y) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    ws.send(JSON.stringify({
        type: 'touch',
        action: action,
        x: x,
        y: y
    }));
}

function sendGlobalCommand(action) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    ws.send(JSON.stringify({
        type: 'global',
        action: action
    }));
}

// Cracked-Screen Rescue Controls
document.getElementById('unlockBtn').addEventListener('click', () => {
    const pin = document.getElementById('pinInput').value.trim();
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'unlock', pin: pin }));
        document.getElementById('pinInput').value = '';
    }
});

document.getElementById('pinInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        document.getElementById('unlockBtn').click();
    }
});

document.getElementById('sendTextBtn').addEventListener('click', () => {
    const text = document.getElementById('textInput').value;
    if (text && ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'text', text: text }));
        document.getElementById('textInput').value = '';
    }
});

document.getElementById('textInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        document.getElementById('sendTextBtn').click();
    }
});

// System Navigation Buttons
document.getElementById('btnBack').addEventListener('click', () => sendGlobalCommand(1));
document.getElementById('btnHome').addEventListener('click', () => sendGlobalCommand(2));
document.getElementById('btnRecents').addEventListener('click', () => sendGlobalCommand(3));
document.getElementById('btnPower').addEventListener('click', () => sendGlobalCommand(4));
document.getElementById('btnNotif').addEventListener('click', () => sendGlobalCommand(5));
document.getElementById('btnQuickSet').addEventListener('click', () => sendGlobalCommand(6));

function launchNativeMirror() {
    fetch('/api/launch-scrcpy', { method: 'POST' })
        .then(res => res.json())
        .then(() => {
            const btn1 = document.getElementById('btnLaunchNative');
            const btn2 = document.getElementById('btnLaunchNativeSidebar');
            if (btn1) btn1.textContent = '✅ Launching Desktop Window...';
            if (btn2) btn2.textContent = '✅ Launching...';
            setTimeout(() => {
                if (btn1) btn1.textContent = '🚀 Launch 60 FPS Native Mirror';
                if (btn2) btn2.textContent = '🚀 Open 60 FPS Mirror';
            }, 3000);
        })
        .catch(err => alert('Failed to launch: ' + err.message));
}

const btnLaunchNative = document.getElementById('btnLaunchNative');
if (btnLaunchNative) btnLaunchNative.addEventListener('click', launchNativeMirror);

const btnLaunchNativeSidebar = document.getElementById('btnLaunchNativeSidebar');
if (btnLaunchNativeSidebar) btnLaunchNativeSidebar.addEventListener('click', launchNativeMirror);

// Start system
configureDecoder('avc1.42001f');
connectWebSocket();

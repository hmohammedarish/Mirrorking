const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const WebSocket = require('ws');

const TCP_PORT = 8888;
const ADB_BRIDGE_PORT = 7777; // Local virtual ADB port for Scrcpy
const HTTP_PORT = 3000;
const PUBLIC_DIR = path.join(__dirname, 'public');
const ADB_PATH = 'e:\\SECUREADY\\tools\\android-sdk\\platform-tools\\adb.exe';

const relayArgIndex = process.argv.indexOf('--relay');
const RELAY_URL = relayArgIndex !== -1 ? process.argv[relayArgIndex + 1] : process.env.RELAY_URL;

let activePhoneSocket = null;
let activeRelayWs = null;
let activeAdbClient = null;
let browserClients = new Set();
let lastDeviceInfo = null;
let lastVideoConfig = null;

// Telemetry
let frameCount = 0;
let bytesReceived = 0;
let lastTelemetryTime = Date.now();
let currentFps = 0;
let currentBitrateKbps = 0;

setInterval(() => {
    const now = Date.now();
    const elapsedSec = (now - lastTelemetryTime) / 1000;
    if (elapsedSec >= 1) {
        currentFps = Math.round(frameCount / elapsedSec);
        currentBitrateKbps = Math.round((bytesReceived * 8) / (elapsedSec * 1000));
        frameCount = 0;
        bytesReceived = 0;
        lastTelemetryTime = now;

        const telemetry = JSON.stringify({
            type: 'telemetry',
            fps: currentFps,
            bitrateKbps: currentBitrateKbps,
            connected: activePhoneSocket !== null && !activePhoneSocket.destroyed
        });

        for (const ws of browserClients) {
            if (ws.readyState === WebSocket.OPEN) {
                ws.send(telemetry);
            }
        }
    }
}, 1000);

// --- 1. TCP Server (Receives Phone Connection) ---
const tcpServer = net.createServer((socket) => {
    console.log(`[TCP] Phone connected from ${socket.remoteAddress}:${socket.remotePort}`);
    activePhoneSocket = socket;
    socket.setNoDelay(true);

    notifyBrowserConnection(true, socket.remoteAddress);

    let incomingBuffer = Buffer.alloc(0);

    function parseIncomingBytes(chunk) {
        bytesReceived += chunk.length;
        incomingBuffer = Buffer.concat([incomingBuffer, chunk]);

        while (incomingBuffer.length >= 18) {
            if (incomingBuffer[0] !== 0x4D || incomingBuffer[1] !== 0x49 || incomingBuffer[2] !== 0x52 || incomingBuffer[3] !== 0x52) {
                incomingBuffer = incomingBuffer.slice(1);
                continue;
            }

            const type = incomingBuffer[4];
            const length = incomingBuffer.readInt32BE(5);

            if (length < 0 || length > 10 * 1024 * 1024) {
                console.warn('[Parser] Invalid packet length:', length, 'resyncing buffer...');
                incomingBuffer = incomingBuffer.slice(4);
                continue;
            }

            const totalPacketLen = 18 + length;

            if (incomingBuffer.length < totalPacketLen) {
                break;
            }

            let pts = 0n;
            try {
                pts = incomingBuffer.readBigInt64BE(9);
            } catch (e) {}

            const flags = incomingBuffer[17];
            const payload = incomingBuffer.slice(18, totalPacketLen);

            try {
                handlePhonePacket(type, flags, pts, payload);
            } catch (err) {
                console.error('[Error handling packet]:', err.message);
            }

            incomingBuffer = incomingBuffer.slice(totalPacketLen);
        }
    }

    socket.on('data', (chunk) => {
        parseIncomingBytes(chunk);
    });

    socket.on('close', (hadError) => {
        console.log(`[TCP] Phone socket closed (hadError=${hadError})`);
        if (activePhoneSocket === socket) {
            activePhoneSocket = null;
            notifyBrowserConnection(false);
        }
    });

    socket.on('error', (err) => {
        console.warn('[TCP] Socket error:', err.message);
    });
});

tcpServer.listen(TCP_PORT, '0.0.0.0', () => {
    console.log(`[TCP Server] Listening for phone on port ${TCP_PORT}...`);
    setupAdbReverse();
});

// --- 2. Cloud Relay Client (Optional: Connects to Render/Fly.io) ---
function connectToRelay(relayHost) {
    let wsUrl = relayHost;
    if (!wsUrl.startsWith('ws://') && !wsUrl.startsWith('wss://')) {
        wsUrl = (wsUrl.includes('localhost') || wsUrl.includes('127.0.0.1')) ? `ws://${wsUrl}` : `wss://${wsUrl}`;
    }
    if (!wsUrl.endsWith('/pc')) {
        wsUrl = wsUrl.replace(/\/$/, '') + '/pc';
    }
    console.log(`[Relay Client] Connecting to Cloud Relay at ${wsUrl}...`);
    const ws = new WebSocket(wsUrl);

    ws.on('open', () => {
        console.log(`[Relay Client] Connected to Cloud Relay successfully!`);
        activeRelayWs = ws;
        notifyBrowserConnection(true, wsUrl);
    });

    ws.on('message', (data) => {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        bytesReceived += buf.length;
        // Parse incoming packet from relay
        let streamBuf = buf;
        while (streamBuf.length >= 18) {
            if (streamBuf[0] !== 0x4D || streamBuf[1] !== 0x49 || streamBuf[2] !== 0x52 || streamBuf[3] !== 0x52) {
                streamBuf = streamBuf.slice(1);
                continue;
            }
            const type = streamBuf[4];
            const length = streamBuf.readInt32BE(5);
            const totalPacketLen = 18 + length;
            if (streamBuf.length < totalPacketLen) break;

            let pts = 0n;
            try { pts = streamBuf.readBigInt64BE(9); } catch (e) {}
            const flags = streamBuf[17];
            const payload = streamBuf.slice(18, totalPacketLen);
            handlePhonePacket(type, flags, pts, payload);
            streamBuf = streamBuf.slice(totalPacketLen);
        }
    });

    ws.on('close', () => {
        console.log(`[Relay Client] Cloud Relay disconnected. Reconnecting in 3 seconds...`);
        activeRelayWs = null;
        notifyBrowserConnection(false);
        setTimeout(() => connectToRelay(relayHost), 3000);
    });

    ws.on('error', (err) => {
        console.warn(`[Relay Client] Socket error: ${err.message}`);
    });
}

if (RELAY_URL) {
    connectToRelay(RELAY_URL);
}

// --- 3. ADB Tunnel Server (Exposes 127.0.0.1:7777 to PC for Scrcpy) ---
const adbBridgeServer = net.createServer((clientSocket) => {
    console.log(`[AdbBridge] PC Scrcpy/ADB client connected on port ${ADB_BRIDGE_PORT}`);
    activeAdbClient = clientSocket;
    clientSocket.setNoDelay(true);

    clientSocket.on('data', (data) => {
        const header = Buffer.alloc(18);
        header.set([0x4D, 0x49, 0x52, 0x52], 0);
        header.writeUInt8(0x20, 4); // CMD_ADB_DATA
        header.writeInt32BE(data.length, 5);
        header.writeBigInt64BE(0n, 9);
        header.writeUInt8(0, 17);
        const fullPacket = Buffer.concat([header, data]);

        if (activeRelayWs && activeRelayWs.readyState === WebSocket.OPEN) {
            activeRelayWs.send(fullPacket);
        } else if (activePhoneSocket && !activePhoneSocket.destroyed) {
            activePhoneSocket.write(fullPacket);
        }
    });

    clientSocket.on('close', () => {
        console.log('[AdbBridge] Scrcpy/ADB client disconnected');
        if (activeAdbClient === clientSocket) {
            activeAdbClient = null;
        }
    });

    clientSocket.on('error', (err) => {
        console.warn('[AdbBridge] Socket error:', err.message);
    });
});

adbBridgeServer.listen(ADB_BRIDGE_PORT, '127.0.0.1', () => {
    console.log(`[AdbBridge] Virtual ADB bridge ready on 127.0.0.1:${ADB_BRIDGE_PORT} (for Scrcpy)`);
});

function handlePhonePacket(type, flags, pts, payload) {
    if (type === 0x20) {
        // Forward raw ADB response to active Scrcpy/ADB client on PC
        if (activeAdbClient && !activeAdbClient.destroyed) {
            activeAdbClient.write(payload);
        }
        return;
    }

    if (type === 0x01) {
        lastVideoConfig = payload;
        console.log(`[Phone] Received SPS/PPS Config (${payload.length} bytes)`);
        broadcastToBrowsers({
            type: 'config',
            data: payload.toString('base64')
        });
    } else if (type === 0x02) {
        frameCount++;
        const isKey = flags === 1;

        let fullNalPayload = payload;
        if (isKey && lastVideoConfig) {
            fullNalPayload = Buffer.concat([lastVideoConfig, payload]);
        }

        if (frameCount % 60 === 1) {
            console.log(`[Phone] Streaming video frame #${frameCount} (size=${fullNalPayload.length}, isKey=${isKey})`);
        }

        const header = Buffer.alloc(9);
        header.writeUInt8(isKey ? 1 : 0, 0);
        try {
            header.writeBigInt64BE(BigInt(pts), 1);
        } catch (e) {
            header.writeBigInt64BE(0n, 1);
        }
        const binaryChunk = Buffer.concat([header, fullNalPayload]);

        for (const ws of browserClients) {
            if (ws.readyState === WebSocket.OPEN) {
                ws.send(binaryChunk);
            }
        }
    } else if (type === 0x03) {
        if (payload.length >= 8) {
            const width = payload.readInt32BE(0);
            const height = payload.readInt32BE(4);
            lastDeviceInfo = { width, height };
            console.log(`[Phone] Screen Resolution: ${width}x${height}`);
            broadcastToBrowsers({
                type: 'device_info',
                width,
                height
            });
        }
    }
}

function broadcastToBrowsers(msgObj) {
    const json = JSON.stringify(msgObj);
    for (const ws of browserClients) {
        if (ws.readyState === WebSocket.OPEN) {
            ws.send(json);
        }
    }
}

function notifyBrowserConnection(connected, remoteIp = '') {
    broadcastToBrowsers({
        type: 'status',
        connected,
        remoteIp,
        isUsb: remoteIp === '127.0.0.1' || remoteIp === '::ffff:127.0.0.1'
    });
}

// --- 3. HTTP Server (Serves Web Controller) ---
const mimeTypes = {
    '.html': 'text/html',
    '.js': 'application/javascript',
    '.css': 'text/css',
    '.png': 'image/png',
    '.svg': 'image/svg+xml'
};

const httpServer = http.createServer((req, res) => {
    let reqPath = req.url.split('?')[0];
    if (reqPath === '/api/launch-scrcpy') {
        const scriptPath = path.join(__dirname, '..', 'launch-scrcpy.bat');
        const proc = spawn('cmd.exe', ['/c', 'start', '""', scriptPath], {
            detached: true,
            stdio: 'ignore',
            cwd: path.join(__dirname, '..')
        });
        proc.unref();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, message: 'Native Scrcpy launched!' }));
        return;
    }

    if (reqPath === '/api/status') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            phoneConnected: activePhoneSocket !== null && !activePhoneSocket.destroyed,
            relayConnected: activeRelayWs !== null && activeRelayWs.readyState === WebSocket.OPEN,
            clients: browserClients.size
        }));
        return;
    }

    const filePath = path.join(PUBLIC_DIR, reqPath);
    if (!filePath.startsWith(PUBLIC_DIR)) {
        res.writeHead(403);
        res.end('Forbidden');
        return;
    }

    fs.readFile(filePath, (err, data) => {
        if (err) {
            res.writeHead(404);
            res.end('Not Found');
            return;
        }
        const ext = path.extname(filePath);
        res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'application/octet-stream' });
        res.end(data);
    });
});

// --- 4. WebSocket Server (Browser <-> PC Server) ---
const wss = new WebSocket.Server({ server: httpServer });

wss.on('connection', (ws) => {
    browserClients.add(ws);
    console.log(`[WS] Browser UI connected (active clients: ${browserClients.size})`);

    ws.send(JSON.stringify({
        type: 'status',
        connected: activePhoneSocket !== null && !activePhoneSocket.destroyed,
        isUsb: activePhoneSocket ? activePhoneSocket.remoteAddress.includes('127.0.0.1') : false
    }));

    if (lastDeviceInfo) {
        ws.send(JSON.stringify({
            type: 'device_info',
            width: lastDeviceInfo.width,
            height: lastDeviceInfo.height
        }));
    }

    if (lastVideoConfig) {
        ws.send(JSON.stringify({
            type: 'config',
            data: lastVideoConfig.toString('base64')
        }));
    }

    ws.on('message', (message) => {
        try {
            const cmd = JSON.parse(message);
            handleBrowserCommand(cmd);
        } catch (e) {
            console.warn('[WS] Invalid command from browser:', e.message);
        }
    });

    ws.on('close', () => {
        browserClients.delete(ws);
        console.log(`[WS] Browser UI closed (active clients: ${browserClients.size})`);
    });
});

function handleBrowserCommand(cmd) {
    if (cmd.type === 'reconnect') {
        setupAdbReverse();
        return;
    }

    const sendCmdToDevice = (buf) => {
        if (activeRelayWs && activeRelayWs.readyState === WebSocket.OPEN) {
            activeRelayWs.send(buf);
        } else if (activePhoneSocket && !activePhoneSocket.destroyed) {
            activePhoneSocket.write(buf);
        }
    };

    if (cmd.type === 'touch') {
        const buf = Buffer.alloc(4 + 1 + 4 + 9);
        buf.set([0x4D, 0x49, 0x52, 0x52], 0);
        buf.writeUInt8(0x10, 4);
        buf.writeInt32BE(9, 5);
        buf.writeUInt8(cmd.action, 9);
        buf.writeFloatBE(cmd.x, 10);
        buf.writeFloatBE(cmd.y, 14);
        sendCmdToDevice(buf);
    } else if (cmd.type === 'global') {
        const buf = Buffer.alloc(4 + 1 + 4 + 1);
        buf.set([0x4D, 0x49, 0x52, 0x52], 0);
        buf.writeUInt8(0x11, 4);
        buf.writeInt32BE(1, 5);
        buf.writeUInt8(cmd.action, 9);
        sendCmdToDevice(buf);
    } else if (cmd.type === 'unlock') {
        const pinBuf = Buffer.from(cmd.pin || '', 'utf8');
        const buf = Buffer.alloc(4 + 1 + 4 + pinBuf.length);
        buf.set([0x4D, 0x49, 0x52, 0x52], 0);
        buf.writeUInt8(0x13, 4);
        buf.writeInt32BE(pinBuf.length, 5);
        pinBuf.copy(buf, 9);
        sendCmdToDevice(buf);
    } else if (cmd.type === 'text') {
        const textBuf = Buffer.from(cmd.text || '', 'utf8');
        const buf = Buffer.alloc(4 + 1 + 4 + textBuf.length);
        buf.set([0x4D, 0x49, 0x52, 0x52], 0);
        buf.writeUInt8(0x12, 4);
        buf.writeInt32BE(textBuf.length, 5);
        textBuf.copy(buf, 9);
        sendCmdToDevice(buf);
    }
}

function setupAdbReverse() {
    if (fs.existsSync(ADB_PATH)) {
        try {
            const proc = spawn(ADB_PATH, ['-d', 'reverse', `tcp:${TCP_PORT}`, `tcp:${TCP_PORT}`]);
            proc.on('close', (code) => {
                if (code === 0) {
                    console.log(`[ADB] USB reverse tunnel established on port ${TCP_PORT}!`);
                }
            });
        } catch (e) {
            console.log('[ADB] Could not run adb reverse automatically:', e.message);
        }
    }
}

httpServer.listen(HTTP_PORT, '0.0.0.0', () => {
    console.log(`=================================================`);
    console.log(`  MirrorKing PC Server is running!`);
    console.log(`  Web Controller: http://localhost:${HTTP_PORT}`);
    console.log(`  Virtual Scrcpy Bridge: 127.0.0.1:${ADB_BRIDGE_PORT}`);
    console.log(`=================================================`);
});

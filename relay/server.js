// MirrorKing 4G/5G Cellular WebSocket Relay Server
// Designed for Render.com Free Tier (Web Service with HTTPS/WSS on port 443)

const http = require('http');
const WebSocket = require('ws');

const PORT = process.env.PORT || 8888;

let phoneWs = null;
let pcWs = null;

// HTTP Server for Render Health Checks & Status Monitoring
const server = http.createServer((req, res) => {
    if (req.url === '/' || req.url === '/healthz') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            status: 'online',
            service: 'MirrorKing Cellular Cloud Relay',
            phoneConnected: phoneWs !== null && phoneWs.readyState === WebSocket.OPEN,
            pcConnected: pcWs !== null && pcWs.readyState === WebSocket.OPEN,
            uptimeSeconds: Math.floor(process.uptime()),
            instructions: {
                phoneEndpoint: '/phone',
                pcEndpoint: '/pc'
            }
        }, null, 2));
        return;
    }

    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
});

// WebSocket Server for High-Speed Streaming
const wss = new WebSocket.Server({ server });

wss.on('connection', (ws, req) => {
    const url = req.url || '';
    const isPhone = url.startsWith('/phone') || url.includes('role=phone');
    const isPc = url.startsWith('/pc') || url.includes('role=pc');

    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });

    if (isPhone) {
        console.log(`[Relay] Phone connected from ${req.socket.remoteAddress}`);
        if (phoneWs && phoneWs !== ws && phoneWs.readyState === WebSocket.OPEN) {
            try { phoneWs.close(1000, 'Replaced by new connection'); } catch (e) {}
        }
        phoneWs = ws;

        ws.on('message', (data, isBinary) => {
            // Forward directly to PC client
            if (pcWs && pcWs.readyState === WebSocket.OPEN) {
                pcWs.send(data, { binary: isBinary });
            }
        });

        ws.on('close', (code, reason) => {
            console.log(`[Relay] Phone disconnected (code=${code})`);
            if (phoneWs === ws) phoneWs = null;
        });

        ws.on('error', (err) => {
            console.warn('[Relay] Phone socket error:', err.message);
        });

    } else if (isPc) {
        console.log(`[Relay] PC client connected from ${req.socket.remoteAddress}`);
        if (pcWs && pcWs !== ws && pcWs.readyState === WebSocket.OPEN) {
            try { pcWs.close(1000, 'Replaced by new connection'); } catch (e) {}
        }
        pcWs = ws;

        ws.on('message', (data, isBinary) => {
            // Forward directly to Phone
            if (phoneWs && phoneWs.readyState === WebSocket.OPEN) {
                phoneWs.send(data, { binary: isBinary });
            }
        });

        ws.on('close', (code, reason) => {
            console.log(`[Relay] PC client disconnected (code=${code})`);
            if (pcWs === ws) pcWs = null;
        });

        ws.on('error', (err) => {
            console.warn('[Relay] PC socket error:', err.message);
        });

    } else {
        // Unknown role, determine by first packet or query
        console.log(`[Relay] Ambiguous client connected from ${req.socket.remoteAddress}. Waiting for identification.`);
        ws.once('message', (firstChunk) => {
            if (Buffer.isBuffer(firstChunk) && firstChunk.length >= 4 &&
                firstChunk[0] === 0x4D && firstChunk[1] === 0x49 && firstChunk[2] === 0x52 && firstChunk[3] === 0x52) {
                // Phone signature "MIRR"
                console.log('[Relay] Identified as Phone via MIRR header');
                phoneWs = ws;
                if (pcWs && pcWs.readyState === WebSocket.OPEN) {
                    pcWs.send(firstChunk, { binary: true });
                }
            } else {
                console.log('[Relay] Identified as PC client');
                pcWs = ws;
                if (phoneWs && phoneWs.readyState === WebSocket.OPEN) {
                    phoneWs.send(firstChunk, { binary: false });
                }
            }
        });
    }
});

// Render Ping/Pong Keep-Alive (every 20s) to prevent reverse-proxy timeout
const heartbeatInterval = setInterval(() => {
    wss.clients.forEach((ws) => {
        if (ws.isAlive === false) {
            return ws.terminate();
        }
        ws.isAlive = false;
        ws.ping();
    });
}, 20000);

wss.on('close', () => {
    clearInterval(heartbeatInterval);
});

server.listen(PORT, '0.0.0.0', () => {
    console.log(`=======================================================`);
    console.log(`  MirrorKing Cloud Relay listening on port ${PORT}`);
    console.log(`  Render Healthcheck: http://0.0.0.0:${PORT}/healthz`);
    console.log(`  Phone Endpoint:     /phone`);
    console.log(`  PC Endpoint:        /pc`);
    console.log(`=======================================================`);
});

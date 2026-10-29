#!/usr/bin/env node
/**
 * harness-wifi-proxy.js —— 让手机通过 Wi-Fi 连上电脑上的 DeepSeek Harness（DSH）
 *
 * 背景：DSH 桌面端只监听电脑本机 127.0.0.1:<端口>，手机无法直接访问。
 *   本脚本在电脑上开一个局域网监听端口（默认 0.0.0.0:3413），把连接原样转发到
 *   **当前正在运行的 DSH 端口**；手机 App 里填 http://<电脑局域网IP>:3413 即可。
 *
 * ⚠ DSH 桌面端每次启动的端口是动态的（可能是 3412、17838、30984……），
 *   所以本脚本不再写死上游端口，而是自动检测「DeepSeek Harness」进程正在监听的
 *   那个 Web 端口（用 /dsh-pet-7340/state 探活确认），DSH 重启后也能自动跟上。
 *
 * 用法（在电脑上运行，保持窗口不关）：
 *   node tools\harness-wifi-proxy.js                     # 自动检测 DSH 端口，监听 3413
 *   node tools\harness-wifi-proxy.js --port 3413
 *   node tools\harness-wifi-proxy.js --upstream 127.0.0.1:30984   # 手动指定上游（一般不用）
 *
 * 首次运行时 Windows 可能会弹「是否允许 Node.js 访问网络」——选择"允许"（专用网络）。
 * 若没弹窗且手机连不上，多半是防火墙拦了：用管理员 PowerShell 执行一次
 *   New-NetFirewallRule -DisplayName "DSH Pet Wi-Fi" -Direction Inbound -Protocol TCP -LocalPort 3413 -Action Allow -Profile Private
 *
 * 停止：Ctrl+C，或直接关掉窗口。
 */
'use strict';

const net = require('net');
const os = require('os');
const http = require('http');
const { execFileSync } = require('child_process');

function argOf(name, fallback) {
  // 同时兼容 --port 和 -port 两种写法
  for (const prefix of ['--', '-']) {
    const i = process.argv.indexOf(prefix + name);
    if (i > 0 && process.argv[i + 1]) return process.argv[i + 1];
  }
  return fallback;
}

const LISTEN_PORT = Number(argOf('port', '3413'));
const EXPLICIT_UPSTREAM = argOf('upstream', '');
const DETECT_RETRY_MS = 4000; // 自动检测失败后的重试间隔
const PROBE_TIMEOUT_MS = 1200;

let cachedTarget = null;   // { host, port }
let inflightDetect = null;
let lastDetectFailAt = 0;
let lastNoTargetLogAt = 0;

function log(msg) {
  const t = new Date().toTimeString().slice(0, 8);
  console.log(`[${t}] ${msg}`);
}

function lanAddresses() {
  const out = [];
  const ifaces = os.networkInterfaces();
  for (const name of Object.keys(ifaces)) {
    for (const info of ifaces[name] || []) {
      if (info && info.family === 'IPv4' && !info.internal) {
        out.push({ name, address: info.address });
      }
    }
  }
  return out;
}

/** 探测某个本机端口是不是当前的 DSH Web 服务（用桌宠插件的 /state 路由确认）。 */
function probeDshPort(port) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (ok) => { if (!settled) { settled = true; resolve(ok); } };
    const req = http.get({
      host: '127.0.0.1',
      port,
      path: '/dsh-pet-7340/state',
      timeout: PROBE_TIMEOUT_MS,
      headers: { 'user-agent': 'dsh-pet-wifi-proxy' },
    }, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { if (body.length < 4000) body += chunk; });
      res.on('end', () => done(res.statusCode === 200 && /"sections"|"balance"/.test(body)));
      res.on('error', () => done(false));
    });
    req.on('timeout', () => { req.destroy(); done(false); });
    req.on('error', () => done(false));
  });
}

/** 列出所有由「DeepSeek Harness.exe」进程监听的 IPv4 端口（带兜底候选）。 */
function candidatePorts() {
  const candidates = [];
  try {
    const netstat = execFileSync('netstat', ['-ano', '-p', 'TCP'], {
      encoding: 'utf8', windowsHide: true, timeout: 6000,
    });
    const listeners = [];
    for (const line of netstat.split(/\r?\n/)) {
      const m = line.trim().match(/^TCP\s+(\S+):(\d+)\s+(\S+)\s+LISTENING\s+(\d+)$/i);
      if (!m) continue;
      const addr = m[1];
      if (addr !== '127.0.0.1' && addr !== '0.0.0.0') continue;
      listeners.push({ port: Number(m[2]), pid: Number(m[4]) });
    }

    const dshPids = new Set();
    try {
      const tlist = execFileSync('tasklist', ['/FO', 'CSV', '/NH'], {
        encoding: 'utf8', windowsHide: true, timeout: 6000,
      });
      for (const line of tlist.split(/\r?\n/)) {
        const cols = line.trim().split('","').map((s) => s.replace(/^"|"$/g, ''));
        if (cols.length >= 2 && cols[0] && cols[0].toLowerCase() === 'deepseek harness.exe') {
          const pid = Number(cols[1]);
          if (Number.isFinite(pid)) dshPids.add(pid);
        }
      }
    } catch (e) {
      log(`读取进程列表失败：${e.message}`);
    }

    if (dshPids.size > 0) {
      for (const l of listeners) if (dshPids.has(l.pid) && !candidates.includes(l.port)) candidates.push(l.port);
    }
  } catch (e) {
    log(`执行 netstat 失败：${e.message}`);
  }

  // 兜底：常见历史端口 + 上次用过的端口（进程名/tasklist 读不到时仍能救回来）
  const fallbacks = [];
  if (cachedTarget) fallbacks.push(cachedTarget.port);
  fallbacks.push(3412, 17838, 30984);
  for (const p of fallbacks) if (Number.isFinite(p) && p > 0 && !candidates.includes(p)) candidates.push(p);
  return candidates;
}

async function detectTarget() {
  if (EXPLICIT_UPSTREAM) {
    const [host, portRaw] = EXPLICIT_UPSTREAM.split(':');
    return { host: host || '127.0.0.1', port: Number(portRaw) || 3412 };
  }
  const candidates = candidatePorts();
  for (const port of candidates) {
    if (await probeDshPort(port)) return { host: '127.0.0.1', port };
  }
  return null;
}

/** 取得当前上游目标（带缓存/在途合并/失败退避）。 */
function ensureTarget() {
  if (cachedTarget) return Promise.resolve(cachedTarget);
  if (inflightDetect) return inflightDetect;
  const now = Date.now();
  if (now - lastDetectFailAt < DETECT_RETRY_MS) return Promise.resolve(null);
  inflightDetect = detectTarget().then((target) => {
    inflightDetect = null;
    if (target) {
      if (!cachedTarget || cachedTarget.port !== target.port) {
        log(`检测到电脑上的 DSH：http://127.0.0.1:${target.port}（端口变化会自动跟随）`);
      }
      cachedTarget = target;
      lastDetectFailAt = 0;
    } else {
      lastDetectFailAt = Date.now();
    }
    return target;
  }).catch((e) => {
    inflightDetect = null;
    lastDetectFailAt = Date.now();
    log(`自动检测 DSH 端口出错：${e.message}`);
    return null;
  });
  return inflightDetect;
}

const server = net.createServer((client) => {
  const who = `${client.remoteAddress}:${client.remotePort}`;
  ensureTarget().then((target) => {
    if (!target) {
      const now = Date.now();
      if (now - lastNoTargetLogAt > 5000) {
        log(`收到手机连接（${who}），但没找到正在运行的 DSH；请确认 DeepSeek Harness 已打开。`);
        lastNoTargetLogAt = now;
      }
      try { client.destroy(); } catch (e) {}
      return;
    }

    let closed = false;
    let reqHead = '';
    let reqLogged = false;
    const upstream = net.connect(target.port, target.host);

    const close = (why) => {
      if (closed) return;
      closed = true;
      log(`断开 ${who}（${why}）`);
      try { client.destroy(); } catch (e) {}
      try { upstream.destroy(); } catch (e) {}
    };

    client.on('error', (e) => close('client ' + e.message));
    upstream.on('error', (e) => {
      // DSH 重启/换端口：清掉缓存，下一次请求自动重新检测
      if (e && (e.code === 'ECONNREFUSED' || e.code === 'ECONNRESET' || e.code === 'ETIMEDOUT')) {
        cachedTarget = null;
        lastDetectFailAt = 0;
      }
      close('upstream ' + e.message);
    });
    client.on('close', () => close('client-close'));
    upstream.on('close', () => close('upstream-close'));
    upstream.on('connect', () => log(`转发 ${who} -> 127.0.0.1:${target.port}`));

    // 只记录 HTTP 请求行（method + path），不记录正文/响应体（隐私）
    client.on('data', (chunk) => {
      if (!reqLogged && reqHead.length < 4096) {
        reqHead += chunk.toString('latin1');
        const lineEnd = reqHead.indexOf('\r\n');
        if (lineEnd > 0) {
          const firstLine = reqHead.slice(0, lineEnd);
          if (/^(GET|POST|PUT|DELETE|HEAD|OPTIONS) \S+ HTTP\//.test(firstLine)) {
            log(`请求 ${firstLine}`);
            reqLogged = true;
          } else if (!/^(GET|POST|PUT|DELETE|HEAD|OPTIONS) /.test(reqHead)) {
            reqLogged = true; // 非 HTTP（不该出现），不再嗅探
          }
        }
      }
    });
    client.pipe(upstream);
    upstream.pipe(client);
  });
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    log(`端口 ${LISTEN_PORT} 已被占用：请换端口（--port xxxx）或先关掉占用程序。`);
  } else {
    log(`监听失败：${e.message}`);
  }
  process.exit(1);
});

server.listen(LISTEN_PORT, '0.0.0.0', async () => {
  log(`代理已启动：手机访问 0.0.0.0:${LISTEN_PORT}，自动转发到电脑上正在运行的 DSH`);
  const addrs = lanAddresses();
  if (addrs.length === 0) {
    log('未找到局域网 IPv4 地址（请确认已连接 Wi-Fi/网线）。');
  } else {
    log('手机 App「连接电脑 DeepSeek Harness」地址填以下之一（手机需与电脑同一 Wi-Fi）：');
    for (const a of addrs) {
      log(`  http://${a.address}:${LISTEN_PORT}    （网卡：${a.name}）`);
    }
  }
  const target = await ensureTarget();
  if (target) log(`当前上游：http://127.0.0.1:${target.port}（DSH 端口变化会自动重新检测）`);
  else log('暂未检测到 DSH；请先打开 DeepSeek Harness，本代理会自动重试。');
  log('保持本窗口运行；手机浏览器可先打开 /dsh-pet-7340/state 验证能看到 JSON。');
});

process.on('SIGINT', () => {
  log('收到 Ctrl+C，正在退出…');
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1000);
});

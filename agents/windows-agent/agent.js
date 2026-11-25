#!/usr/bin/env node
/**
 * وكيل المراقبة لأجهزة الكمبيوتر (ويندوز) — Node.js 18+
 * ------------------------------------------------------
 * يرسل إلى الخادم المركزي:
 *  1) كل مواقع الويب المفتوحة من أي متصفح (حتى الوضع الخفي) عبر إضافة المتصفح المرفقة
 *  2) سجل تصفح المتصفحات كاحتياط (Chrome / Edge / Brave / Opera / Firefox) — لا يشمل الوضع الخفي
 *  3) فتح التطبيقات والنافذة النشطة حاليًا (عبر window-monitor.ps1)
 *
 * التشغيل: node agent.js
 * أول مرة: سيطلب عنوان الخادم + كود الاقتران + اسم الجهاز ثم يحفظها في config.json
 * الاعتماديات: لا شيء إلزامي — better-sqlite3 اختياري لقراءة سجل المتصفحات
 */
const fs = require("fs");
const path = require("path");
const http = require("http");
const { spawn } = require("child_process");
const readline = require("readline");

const BASE_DIR = __dirname;
const CONFIG_PATH = path.join(BASE_DIR, "config.json");
const LOCAL_PORT = 48452; // المنفذ المحلي الذي تستمع عليه إضافة المتصفح
const SEND_INTERVAL = 5000; // إرسال كل 5 ثوانٍ
const HEARTBEAT_INTERVAL = 60000; // نبضة اتصال كل دقيقة
const HISTORY_INTERVAL = 60000; // قراءة سجل المتصفحات كل دقيقة

let CONFIG = {};
const EVENTS = []; // قائمة الأحداث في انتظار الإرسال
const SEEN = new Map(); // منع التكرار
let HISTORY_MODULE = null; // better-sqlite3 إن وُجد

// ---------------- الإعدادات ----------------
function loadConfig() {
  try {
    CONFIG = JSON.parse(fs.readFileSync(CONFIG_PATH, "utf-8"));
  } catch {
    CONFIG = {};
  }
}

function saveConfig() {
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(CONFIG, null, 2), "utf-8");
}

function ask(question) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((resolve) => rl.question(question, (a) => { rl.close(); resolve(a.trim()); }));
}

async function registerDevice() {
  console.log("=== إعداد الجهاز لأول مرة ===");
  if (!CONFIG.server_url) CONFIG.server_url = (await ask("عنوان الخادم (مثال: https://xxxx.vercel.app): ")) || "";
  if (!CONFIG.device_name)
    CONFIG.device_name =
      (await ask(`اسم الجهاز كما سيظهر في اللوحة [${process.env.COMPUTERNAME || "windows-pc"}]: `)) ||
      process.env.COMPUTERNAME || "windows-pc";
  while (true) {
    const code = await ask("كود الاقتران (من إعدادات اللوحة): ");
    if (!code) continue;
    try {
      const res = await fetch(CONFIG.server_url.replace(/\/$/, "") + "/api/device/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ pairingCode: code, name: CONFIG.device_name, platform: "windows" }),
      });
      if (!res.ok) {
        const t = await res.text();
        console.error(`[!] فشل التسجيل (${res.status}): ${t}`);
        continue;
      }
      const data = await res.json();
      CONFIG.api_key = data.apiKey;
      saveConfig();
      console.log("[+] تم تسجيل الجهاز بنجاح وحفظ المفتاح في config.json");
      return;
    } catch (e) {
      console.error("[!] تعذر الوصول للخادم:", e.message);
    }
  }
}

// ---------------- إضافة الأحداث ----------------
function nowIso() {
  return new Date().toISOString();
}

function addEvent(type, fields) {
  const ev = { type, ...fields, occurredAt: fields.occurredAt || nowIso() };
  const fp = `${type}|${ev.url || ev.app || ev.phone || ev.title || ""}|${Math.floor(Date.now() / 10000)}`;
  const now = Date.now();
  for (const [k, t] of SEEN) if (t < now - 3600000) SEEN.delete(k);
  if (SEEN.has(fp)) return;
  SEEN.set(fp, now);
  EVENTS.push(ev);
  if (EVENTS.length > 5000) EVENTS.splice(0, EVENTS.length - 5000);
}

// ---------------- مستقبل إضافة المتصفح (يشمل الوضع الخفي) ----------------
function startExtensionServer() {
  const server = http.createServer((req, res) => {
    if (req.method !== "POST" || req.url !== "/extension") {
      res.writeHead(404);
      res.end();
      return;
    }
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      try {
        const { events = [] } = JSON.parse(body);
        for (const ev of events) {
          if (ev.url && ev.url.startsWith("http")) {
            addEvent("BROWSER", {
              url: ev.url,
              title: ev.title || null,
              app: ev.browser || "متصفح",
              incognito: ev.incognito === true,
            });
          }
        }
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end('{"ok":true}');
      } catch (e) {
        console.error("[!] خطأ في استقبال حدث من الإضافة:", e.message);
        res.writeHead(500);
        res.end();
      }
    });
  });
  server.listen(LOCAL_PORT, "127.0.0.1", () => {
    console.log(`[+] مستقبل إضافة المتصفح يعمل على المنفذ ${LOCAL_PORT} — فعّل الإضافة بما فيها الوضع الخفي`);
  });
}

// ---------------- النافذة النشطة وفتح التطبيقات (ويندوز) ----------------
function startWindowMonitor() {
  if (process.platform !== "win32") {
    console.log("[i] مراقبة النوافذ والتطبيقات تعمل على ويندوز فقط (السكريبت حاليًا على نظام آخر).");
    return;
  }
  let lastApp = null;
  let knownProcs = null;

  const start = () => {
    const child = spawn("powershell.exe", [
      "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", path.join(BASE_DIR, "window-monitor.ps1"),
    ], { windowsHide: true });

    let buf = "";
    child.stdout.setEncoding("utf-8");
    child.stdout.on("data", (chunk) => {
      buf += chunk;
      let idx;
      while ((idx = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, idx).trim();
        buf = buf.slice(idx + 1);
        if (!line) continue;
        try {
          const info = JSON.parse(line);
          // النافذة النشطة تغيّرت → حدث فتح تطبيق
          if (info.p && info.p !== lastApp) {
            lastApp = info.p;
            addEvent("APP", { app: info.p, title: (info.t || "").slice(0, 300) || null });
          }
          // التطبيقات الجديدة المفتوحة
          if (Array.isArray(info.a)) {
            const current = new Set(info.a);
            if (knownProcs) {
              let count = 0;
              for (const p of current) {
                if (!knownProcs.has(p) && count < 20) {
                  if (!/^(svchost|conhost|csrss|dwm|runtime|explorer)/i.test(p)) {
                    addEvent("APP", { app: p, packageName: p, title: "تم فتح التطبيق" });
                    count++;
                  }
                }
              }
            }
            knownProcs = current;
          }
        } catch { /* سطر غير كامل */ }
      }
    });
    child.on("exit", () => {
      // إعادة التشغيل لو توقفت العملية
      setTimeout(start, 3000);
    });
  };
  start();
}

// ---------------- قراءة سجل المتصفحات (احتياط بدون الإضافة) ----------------
const BROWSER_DBS = [
  { app: "Chrome", rel: "Google/Chrome/User Data/Default/History" },
  { app: "Edge", rel: "Microsoft/Edge/User Data/Default/History" },
  { app: "Brave", rel: "BraveSoftware/Brave-Browser/User Data/Default/History" },
  { app: "Opera", rel: "Opera Software/Opera Stable/History" },
];
const CHROME_EPOCH_OFFSET = 11644473600; // ثوانٍ بين 1601 و 1970

function startHistoryMonitor() {
  try {
    HISTORY_MODULE = require("better-sqlite3");
  } catch {
    console.log("[i] سجل المتصفحات الاحتياطي متوقف (better-sqlite3 غير مثبت) — الإضافة تكفي للتغطية الكاملة.");
    return;
  }
  const lad = process.env.LOCALAPPDATA || "";
  if (!lad) return;
  const lastTs = {};
  const start = Date.now();
  for (const b of BROWSER_DBS) lastTs[b.app] = start;

  setInterval(() => {
    for (const b of BROWSER_DBS) {
      try {
        const dbPath = path.join(lad, b.rel);
        if (!fs.existsSync(dbPath)) continue;
        const since = lastTs[b.app];
        const tmp = path.join(require("os").tmpdir(), `mon_${b.app}_${Date.now()}`);
        fs.copyFileSync(dbPath, tmp);
        let rows = [];
        try {
          const db = new HISTORY_MODULE(tmp, { readonly: true });
          rows = db
            .prepare(
              "SELECT url, title, last_visit_time FROM urls WHERE last_visit_time > ? ORDER BY last_visit_time ASC"
            )
            .all(Math.round((since + CHROME_EPOCH_OFFSET) * 1e6));
          db.close();
        } finally {
          fs.unlinkSync(tmp);
        }
        for (const row of rows) {
          const tsSec = row.last_visit_time / 1e6 - CHROME_EPOCH_OFFSET;
          lastTs[b.app] = Math.max(lastTs[b.app], tsSec);
          addEvent("BROWSER", { url: row.url, title: row.title || null, app: b.app, incognito: false });
        }
      } catch { /* المتصفح مفتوح أو الملف مشغول — نجرب لاحقًا */ }
    }
  }, HISTORY_INTERVAL);
}

// ---------------- الإرسال الدوري للخادم ----------------
function startSender() {
  let lastHeartbeat = 0;
  setInterval(async () => {
    if (!CONFIG.api_key) return;
    const base = CONFIG.server_url.replace(/\/$/, "");

    const batch = EVENTS.splice(0, 500);
    if (batch.length) {
      try {
        const res = await fetch(base + "/api/device/events", {
          method: "POST",
          headers: { "Content-Type": "application/json", "X-Device-Key": CONFIG.api_key },
          body: JSON.stringify({ events: batch }),
        });
        if (!res.ok) throw new Error(`status ${res.status}`);
      } catch (e) {
        console.error("[!] فشل الإرسال، سيُعاد المحاولة:", e.message);
        EVENTS.unshift(...batch);
      }
    }

    if (Date.now() - lastHeartbeat > HEARTBEAT_INTERVAL) {
      lastHeartbeat = Date.now();
      try {
        await fetch(base + "/api/device/heartbeat", {
          method: "POST",
          headers: { "Content-Type": "application/json", "X-Device-Key": CONFIG.api_key },
          body: "{}",
        });
      } catch { /* تجاهل */ }
    }
  }, SEND_INTERVAL);
}

// ---------------- التشغيل ----------------
async function main() {
  console.log("=== وكيل المراقبة — جهاز الكمبيوتر (Node.js) ===");
  loadConfig();
  if (!CONFIG.api_key) await registerDevice();
  else console.log(`[+] الجهاز مسجل بالفعل: ${CONFIG.device_name}`);

  startExtensionServer();
  startWindowMonitor();
  startHistoryMonitor();
  startSender();
  console.log("[+] المراقبة تعمل — اترك النافذة مفتوحة.");
}

main().catch((e) => {
  console.error("[!] خطأ حرج:", e);
  process.exit(1);
});

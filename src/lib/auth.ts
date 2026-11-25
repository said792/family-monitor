import { createHmac, randomBytes, scryptSync, timingSafeEqual } from "crypto";
import { db } from "@/lib/db";

const SESSION_COOKIE = "sa_session";
const SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7 أيام

// ---------- إعدادات النظام ----------

function hashPassword(password: string): string {
  const salt = randomBytes(16).toString("hex");
  const hash = scryptSync(password, salt, 64).toString("hex");
  return `${salt}:${hash}`;
}

function verifyPassword(password: string, stored: string): boolean {
  const [salt, hash] = stored.split(":");
  if (!salt || !hash) return false;
  const candidate = scryptSync(password, salt, 64);
  const expected = Buffer.from(hash, "hex");
  return candidate.length === expected.length && timingSafeEqual(candidate, expected);
}

export async function getSetting(key: string): Promise<string | null> {
  const row = await db.setting.findUnique({ where: { key } });
  return row?.value ?? null;
}

export async function setSetting(key: string, value: string) {
  await db.setting.upsert({
    where: { key },
    update: { value },
    create: { key, value },
  });
}

/** تهيئة الإعدادات الافتراضية (كلمة مرور مبدئية + مفتاح تشفير الجلسات) */
export async function ensureSettings() {
  let secret = await getSetting("session_secret");
  if (!secret) {
    secret = randomBytes(32).toString("hex");
    await setSetting("session_secret", secret);
  }
  const pwd = await getSetting("admin_password");
  if (!pwd) {
    await setSetting("admin_password", hashPassword("admin123"));
    await setSetting("admin_password_is_default", "1");
  }
}

export async function verifyAdminPassword(password: string): Promise<boolean> {
  const stored = await getSetting("admin_password");
  if (!stored) return false;
  return verifyPassword(password, stored);
}

export async function changeAdminPassword(newPassword: string) {
  await setSetting("admin_password", hashPassword(newPassword));
  await setSetting("admin_password_is_default", "0");
}

export async function isDefaultPassword(): Promise<boolean> {
  return (await getSetting("admin_password_is_default")) === "1";
}

// ---------- جلسة الجهاز الأساسي (Admin) ----------

async function getSecret(): Promise<string> {
  const s = await getSetting("session_secret");
  return s ?? "fallback-secret";
}

export async function createSessionToken(): Promise<{ value: string; maxAge: number }> {
  const secret = await getSecret();
  const exp = Date.now() + SESSION_TTL_MS;
  const sig = createHmac("sha256", secret).update(`admin.${exp}`).digest("hex");
  return { value: `admin.${exp}.${sig}`, maxAge: SESSION_TTL_MS / 1000 };
}

export async function verifySessionToken(token: string | undefined): Promise<boolean> {
  if (!token) return false;
  const parts = token.split(".");
  if (parts.length !== 3 || parts[0] !== "admin") return false;
  const exp = Number(parts[1]);
  if (!Number.isFinite(exp) || exp < Date.now()) return false;
  const secret = await getSecret();
  const expected = createHmac("sha256", secret).update(`admin.${exp}`).digest("hex");
  const a = Buffer.from(parts[2], "hex");
  const b = Buffer.from(expected, "hex");
  return a.length === b.length && timingSafeEqual(a, b);
}

export { SESSION_COOKIE };

// ---------- مصادقة الأجهزة الفرعية ----------

export async function authenticateDevice(req: Request) {
  const key = req.headers.get("x-device-key");
  if (!key) return null;
  const device = await db.device.findUnique({ where: { apiKey: key } });
  if (!device || device.status !== "active") return null;
  return device;
}

// ---------- تصنيف عمليات البحث ----------

const SEARCH_ENGINES: { pattern: RegExp; param: string; name: string }[] = [
  { pattern: /https?:\/\/(www\.)?google\.[a-z.]+\/search/i, param: "q", name: "Google" },
  { pattern: /https?:\/\/(www\.)?bing\.com\/search/i, param: "q", name: "Bing" },
  { pattern: /https?:\/\/duckduckgo\.com/i, param: "q", name: "DuckDuckGo" },
  { pattern: /https?:\/\/search\.yahoo\.com/i, param: "p", name: "Yahoo" },
  { pattern: /https?:\/\/(www\.)?yandex\.[a-z.]+\/search/i, param: "text", name: "Yandex" },
  { pattern: /https?:\/\/(www\.)?baidu\.com\/s/i, param: "wd", name: "Baidu" },
  { pattern: /https?:\/\/(www\.)?ecosia\.org\/search/i, param: "q", name: "Ecosia" },
  { pattern: /https?:\/\/(www\.)?startpage\.com/i, param: "query", name: "Startpage" },
  { pattern: /https?:\/\/search\.brave\.com/i, param: "q", name: "Brave" },
];

/** يحدد إن كان الرابط عملية بحث ويستخرج نص البحث */
export function classifySearch(url: string): { isSearch: boolean; query: string | null; engine: string | null } {
  for (const engine of SEARCH_ENGINES) {
    if (engine.pattern.test(url)) {
      try {
        const u = new URL(url);
        const q = u.searchParams.get(engine.param);
        if (q) return { isSearch: true, query: q, engine: engine.name };
      } catch {
        /* تجاهل الروابط غير الصالحة */
      }
      return { isSearch: true, query: null, engine: engine.name };
    }
  }
  return { isSearch: false, query: null, engine: null };
}

// ---------- المتصفحات المعروفة (تُستثنى من أحداث «التطبيقات» لتوفير المساحة) ----------

export const KNOWN_BROWSERS = new Set([
  "com.android.chrome", // Chrome
  "com.chrome.beta",
  "com.chrome.dev",
  "com.chrome.canary",
  "com.google.android.googlequicksearchbox", // تطبيق بحث جوجل (يشغل كروم أيضًا)
  "org.mozilla.firefox", // Firefox
  "org.mozilla.focus",
  "org.mozilla.fenix",
  "com.microsoft.emmx", // Edge
  "com.opera.browser", // Opera
  "com.opera.mini",
  "com.opera.touch",
  "com.brave.browser", // Brave
  "com.sec.android.app.sbrowser", // Samsung Internet
  "com.sec.android.app.sbrowser.beta",
  "com.android.browser", // متصفح الأندرويد القديم
  "com.android.browser.beta",
  "com.ucbrowser.international", // UC
  "com.UCMobile.intl",
  "com.vivaldi.browser", // Vivaldi
  "org.torproject.torbrowser", // Tor
  "com.duckduckgo.mobile.android", // DuckDuckGo
  "com.yandex.browser", // Yandex
  "com.kiwibrowser.browser", // Kiwi
  "com.ecosia.android", // Ecosia
  "com.naver.whale", // Whale
  "com.cookiegames.smartcookie", // SmartCookieWeb
  "org.waterfox.project.waterfox", // Waterfox
  "com.linkbubble.playstore", // LinkBubble
  "mark.via.gp", // Via
  "com.mmbox.browser",
  "com.ghostery.android.ghostery",
  "org.mozilla.klar",
  "com.tencent.mm.browser",
]);

/** هل الحزمة متصفح معروف؟ (فتح المتصفح يظهر تلقائيًا ضمن عمليات البحث فلا نكرره) */
export function isKnownBrowser(packageName: string | null | undefined): boolean {
  if (!packageName) return false;
  return KNOWN_BROWSERS.has(packageName.toLowerCase().trim());
}

// ---------- مفتاح التجميع الموحد (نفس منطق migration-v1.4-groupkey.sql) ----------

function domainOf(url: string | null): string | null {
  if (!url) return null;
  const m = url.match(/\/\/([^/#?]+)/);
  return m ? m[1].replace(/^www\./i, "").toLowerCase() : null;
}

/**
 * مفتاح التجميع الموحّد لكل الأنواع:
 * SEARCH   → نص البحث نفسه (أو الدومين إن لم يتوفر)
 * BROWSER  → دومين الموقع بدون www
 * CALL     → رقم الهاتف (أرقام فقط)
 * WHATSAPP → اسم المرسل/المحادثة
 * APP      → اسم حزمة التطبيق
 */
export function computeGroupKey(type: string, ev: { title?: string | null; url?: string | null; phone?: string | null; packageName?: string | null; app?: string | null }): string | null {
  switch (type) {
    case "SEARCH": {
      const t = (ev.title ?? "").trim().toLowerCase();
      if (t) return t;
      const d = domainOf(ev.url ?? null);
      if (d) return d;
      return (ev.url ?? "").trim().toLowerCase() || null;
    }
    case "BROWSER": {
      const d = domainOf(ev.url ?? null);
      if (d) return d;
      return (ev.url ?? "").trim().toLowerCase() || null;
    }
    case "CALL": {
      const digits = (ev.phone ?? "").replace(/[^\d+]/g, "");
      return digits || ev.phone || null;
    }
    case "WHATSAPP": {
      const t = (ev.title ?? "").trim();
      return t || null;
    }
    case "APP": {
      return (ev.packageName ?? "").trim() || (ev.app ?? "").trim() || null;
    }
    default:
      return null;
  }
}

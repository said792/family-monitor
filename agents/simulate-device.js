#!/usr/bin/env node
/**
 * محاكي جهاز فرعي — لتجربة النظام بسرعة من أي كمبيوتر (Node.js 18+، بدون أي مكتبات).
 *
 * الاستخدام:
 *   node simulate-device.js <عنوان الخادم> <كود الاقتران> [اسم الجهاز] [نوع النظام]
 *
 * مثال:
 *   node simulate-device.js https://xxxx.vercel.app 572610 "جهاز تجريبي" windows
 */
const DEMO_EVENTS = [
  { type: "BROWSER", url: "https://www.google.com/search?q=اختبار+النظام", app: "Chrome", incognito: true },
  { type: "BROWSER", url: "https://github.com/zai-org", title: "GitHub", app: "Chrome", incognito: false },
  { type: "APP", app: "Notepad", packageName: "notepad.exe" },
  { type: "CALL", direction: "INCOMING", phone: "+201000000000", durationSec: 120 },
];

const [server, code, name = "جهاز تجريبي", platform = "windows"] = process.argv.slice(2);
if (!server || !code) {
  console.log(__filename.replace(/^.*[\\/]/, "") + " <عنوان الخادم> <كود الاقتران> [اسم الجهاز] [نوع النظام]");
  process.exit(1);
}

async function main() {
  const base = server.replace(/\/$/, "");
  const res = await fetch(base + "/api/device/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ pairingCode: code, name, platform }),
  });
  if (!res.ok) {
    console.error(`[!] فشل التسجيل (${res.status}):`, await res.text());
    process.exit(1);
  }
  const { apiKey } = await res.json();
  console.log("[+] تم تسجيل الجهاز (المفتاح محفوظ مؤقتًا في الذاكرة)");

  let n = 0;
  while (true) {
    const ev = { ...DEMO_EVENTS[n % DEMO_EVENTS.length], occurredAt: new Date().toISOString() };
    n++;
    const r = await fetch(base + "/api/device/events", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Device-Key": apiKey },
      body: JSON.stringify({ events: [ev] }),
    });
    if (!r.ok) {
      console.error(`[!] فشل الإرسال (${r.status})`);
    } else {
      console.log(`[+] أُرسل حدث (${n}): ${ev.url || ev.app || ev.phone}`);
    }
    await new Promise((s) => setTimeout(s, 10000));
  }
}

main().catch((e) => {
  console.error("[!]", e.message);
  process.exit(1);
});

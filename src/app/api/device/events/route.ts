import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { authenticateDevice, classifySearch, computeGroupKey, isKnownBrowser } from "@/lib/auth";

type IncomingEvent = {
  type?: string; // SEARCH | BROWSER | CALL | APP | WHATSAPP (SEARCH سيتم استنتاجه تلقائيًا للروابط)
  title?: string;
  url?: string;
  app?: string;
  packageName?: string;
  direction?: string;
  phone?: string;
  durationSec?: number;
  incognito?: boolean;
  occurredAt?: string;
  meta?: unknown;
};

const ALLOWED_DIRECTIONS = ["INCOMING", "OUTGOING", "MISSED"];
const ALLOWED_TYPES = ["SEARCH", "BROWSER", "CALL", "APP", "WHATSAPP"];

/**
 * رفع دفعة أحداث من الجهاز الفرعي.
 * Header: X-Device-Key
 * Body: { events: [...] } — حتى 500 حدث في الدفعة
 *
 * ملاحظات:
 * - كل الأحداث تُخزَّن كاملة (لا يوجد حذف تلقائي ولا تقليم) — الحذف للأدمن فقط.
 * - أحداث فتح المتصفحات المعروفة (كروم وغيره) تُهمل هنا: فتح المتصفح يظهر
 *   تلقائيًا ضمن عمليات البحث نفسها، فلا داعي لتسجيله في التطبيقات (توفير).
 * - لكل حدث يُحسب groupKey موحّد يُستخدم لتجميع السجلات على الخادم.
 */
export async function POST(req: NextRequest) {
  const device = await authenticateDevice(req);
  if (!device) {
    return NextResponse.json({ error: "مفتاح الجهاز غير صالح" }, { status: 401 });
  }

  const body = await req.json().catch(() => null);
  const events: IncomingEvent[] = Array.isArray(body?.events) ? body.events : [];
  if (events.length === 0) {
    return NextResponse.json({ ok: true, stored: 0 });
  }
  if (events.length > 500) {
    return NextResponse.json({ error: "الحد الأقصى 500 حدث في الدفعة" }, { status: 400 });
  }

  const rows: {
    deviceId: string;
    type: string;
    title: string | null;
    url: string | null;
    app: string | null;
    packageName: string | null;
    direction: string | null;
    phone: string | null;
    durationSec: number | null;
    incognito: boolean;
    occurredAt: Date;
    meta: string | null;
    groupKey: string | null;
  }[] = [];

  let skippedBrowsers = 0;

  for (const ev of events) {
    let type = (ev.type ?? "").toUpperCase();
    let title = typeof ev.title === "string" && ev.title.trim() ? ev.title.slice(0, 300) : null;
    const url = typeof ev.url === "string" && ev.url ? ev.url.slice(0, 2000) : null;
    const packageName = typeof ev.packageName === "string" ? ev.packageName.slice(0, 150) : null;

    // استثناء المتصفحات المعروفة من أحداث «فتح التطبيق» (البيانات نفسها تظهر في البحث/التصفح)
    if (type === "APP" && isKnownBrowser(packageName ?? ev.app)) {
      skippedBrowsers++;
      continue;
    }

    // تصنيف تلقائي: إذا كان رابط متصفح، نحدد إن كانت عملية بحث
    if (url && (type === "BROWSER" || type === "SEARCH" || type === "")) {
      const cls = classifySearch(url);
      if (cls.isSearch) {
        type = "SEARCH";
        if (cls.query) title = cls.query;
        if (cls.engine && !ev.app) ev.app = cls.engine;
      } else if (type !== "BROWSER") {
        type = "BROWSER";
      }
    }

    if (!ALLOWED_TYPES.includes(type)) continue;

    const occurredAt =
      typeof ev.occurredAt === "string" && !isNaN(Date.parse(ev.occurredAt))
        ? new Date(ev.occurredAt)
        : new Date();

    const meta = ev.meta ? JSON.stringify(ev.meta).slice(0, 4000) : null;

    const row = {
      deviceId: device.id,
      type,
      title,
      url,
      app: typeof ev.app === "string" ? ev.app.slice(0, 120) : null,
      packageName,
      direction:
        typeof ev.direction === "string" && ALLOWED_DIRECTIONS.includes(ev.direction)
          ? ev.direction
          : null,
      phone: typeof ev.phone === "string" ? ev.phone.slice(0, 40) : null,
      durationSec: typeof ev.durationSec === "number" && ev.durationSec >= 0 ? Math.round(ev.durationSec) : null,
      incognito: ev.incognito === true,
      occurredAt,
      meta,
    };

    rows.push({ ...row, groupKey: computeGroupKey(type, row) });
  }

  if (rows.length > 0) {
    await db.event.createMany({ data: rows });
    await db.device.update({
      where: { id: device.id },
      data: { lastSeen: new Date() },
    });
  }

  return NextResponse.json({ ok: true, stored: rows.length, skippedBrowsers });
}

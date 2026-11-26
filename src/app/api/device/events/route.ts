import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { authenticateDevice, classifySearch } from "@/lib/auth";

type IncomingEvent = {
  type?: string; // SEARCH | BROWSER | CALL | APP | WHATSAPP | MESSENGER | MEDIA
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

/**
 * رفع دفعة أحداث من الجهاز الفرعي.
 * Header: X-Device-Key
 * Body: { events: [...] } — حتى 500 حدث في الدفعة
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
  }[] = [];

  for (const ev of events) {
    let type = (ev.type ?? "").toUpperCase();
    let title = typeof ev.title === "string" ? ev.title.slice(0, 300) : null;
    const url = typeof ev.url === "string" && ev.url ? ev.url.slice(0, 2000) : null;

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

    if (!["SEARCH", "BROWSER", "CALL", "APP", "WHATSAPP", "MESSENGER", "MEDIA"].includes(type)) continue;

    const occurredAt =
      typeof ev.occurredAt === "string" && !isNaN(Date.parse(ev.occurredAt))
        ? new Date(ev.occurredAt)
        : new Date();

    rows.push({
      deviceId: device.id,
      type,
      title,
      url,
      app: typeof ev.app === "string" ? ev.app.slice(0, 120) : null,
      packageName: typeof ev.packageName === "string" ? ev.packageName.slice(0, 150) : null,
      direction:
        typeof ev.direction === "string" && ALLOWED_DIRECTIONS.includes(ev.direction)
          ? ev.direction
          : null,
      phone: typeof ev.phone === "string" ? ev.phone.slice(0, 40) : null,
      durationSec: typeof ev.durationSec === "number" && ev.durationSec >= 0 ? Math.round(ev.durationSec) : null,
      incognito: ev.incognito === true,
      occurredAt,
      meta: ev.meta ? JSON.stringify(ev.meta).slice(0, 4000) : null,
    });
  }

  if (rows.length > 0) {
    await db.event.createMany({ data: rows });
    await db.device.update({
      where: { id: device.id },
      data: { lastSeen: new Date() },
    });
  }

  return NextResponse.json({ ok: true, stored: rows.length });
}

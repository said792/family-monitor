import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { verifySessionToken, SESSION_COOKIE, KNOWN_BROWSERS } from "@/lib/auth";

const TYPES = ["SEARCH", "BROWSER", "CALL", "APP", "WHATSAPP"] as const;
const PAGE = 300; // حجم الصفحة — لا يوجد سقف إجمالي، التحميل يستمر حتى آخر سجل

/**
 * قراءة السجلات (الجهاز الأساسي فقط).
 * باراميترات:
 *  deviceId — حصر جهاز
 *  type     — SEARCH | BROWSER | CALL | APP | WHATSAPP
 *  group    — مفتاح مجموعة (لتوسيع مجموعة وعرض كل سجلاتها)
 *  q        — بحث نصي
 *  from/to  — نطاق اليوم (ISO)
 *  offset   — إزاحة الترقيم (تحميل المزيد)
 */
export async function GET(req: NextRequest) {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  if (!(await verifySessionToken(token))) {
    return NextResponse.json({ error: "غير مصرح" }, { status: 401 });
  }

  const sp = req.nextUrl.searchParams;
  const deviceId = sp.get("deviceId") || undefined;
  const type = sp.get("type");
  const group = sp.get("group")?.trim();
  const q = sp.get("q")?.trim();
  const from = sp.get("from");
  const to = sp.get("to");
  const offset = Math.max(Number(sp.get("offset")) || 0, 0);

  const where: Record<string, unknown> = {};
  if (deviceId) where.deviceId = deviceId;
  if (type && (TYPES as readonly string[]).includes(type)) where.type = type;
  if (group) where.groupKey = group;
  if (from || to) {
    const occurredAt: Record<string, Date> = {};
    if (from && !isNaN(Date.parse(from))) occurredAt.gte = new Date(from);
    if (to && !isNaN(Date.parse(to))) occurredAt.lt = new Date(to);
    where.occurredAt = occurredAt;
  }
  if (q) {
    where.OR = [
      { title: { contains: q } },
      { url: { contains: q } },
      { app: { contains: q } },
      { phone: { contains: q } },
      { packageName: { contains: q } },
    ];
  }
  // استثناء أحداث فتح المتصفحات المعروفة من كل العروض (تظهر تفاصيلها في البحث/التصفح)
  where.NOT = [{ type: "APP", packageName: { in: [...KNOWN_BROWSERS] } }];

  const [events, total] = await Promise.all([
    db.event.findMany({
      where,
      orderBy: { occurredAt: "desc" },
      take: PAGE,
      skip: offset,
      include: { device: { select: { name: true, platform: true } } },
    }),
    db.event.count({ where }),
  ]);

  return NextResponse.json({
    events,
    total,
    offset,
    hasMore: offset + events.length < total,
  });
}


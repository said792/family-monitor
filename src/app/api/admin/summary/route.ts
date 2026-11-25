import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { verifySessionToken, SESSION_COOKIE, KNOWN_BROWSERS } from "@/lib/auth";

const TYPES = ["SEARCH", "BROWSER", "CALL", "APP", "WHATSAPP"] as const;
const PAGE = 60; // عدد المجموعات في الصفحة

/**
 * تجميع السجلات على الخادم (الجهاز الأساسي فقط).
 * يجمع كل السجلات — مهما كان عددها (50 أو 5000) — في مجموعات:
 *  SEARCH/BROWSER → نص البحث / الدومين
 *  CALL           → رقم الهاتف
 *  WHATSAPP       → اسم المرسل/المحادثة
 *  APP            → التطبيق
 * باراميترات: deviceId, type, q, from/to, offset
 * يعيد: { groups: [{ groupKey, count, lastAt, sample }], total, hasMore }
 */
export async function GET(req: NextRequest) {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  if (!(await verifySessionToken(token))) {
    return NextResponse.json({ error: "غير مصرح" }, { status: 401 });
  }

  const sp = req.nextUrl.searchParams;
  const deviceId = sp.get("deviceId") || undefined;
  const type = sp.get("type");
  const q = sp.get("q")?.trim();
  const from = sp.get("from");
  const to = sp.get("to");
  const offset = Math.max(Number(sp.get("offset")) || 0, 0);

  const where: Record<string, unknown> = {
    groupKey: { not: null },
  };
  if (deviceId) where.deviceId = deviceId;
  if (type && (TYPES as readonly string[]).includes(type)) where.type = type;
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
  // استثناء المتصفحات المعروفة من التطبيقات (توفير — تفاصيلها في البحث/التصفح)
  if (!type || type === "APP" || type === "ALL") {
    where.NOT = [{ type: "APP", packageName: { in: [...KNOWN_BROWSERS] } }];
  }

  const [page, distinctRows] = await Promise.all([
    db.event.groupBy({
      by: ["groupKey"],
      where,
      _count: { _all: true },
      _max: { occurredAt: true },
      orderBy: { _max: { occurredAt: "desc" } },
      take: PAGE,
      skip: offset,
    }),
    db.event.findMany({
      where,
      distinct: ["groupKey"],
      select: { groupKey: true },
    }),
  ]);

  const keys = page.map((g) => g.groupKey).filter((k): k is string => k !== null);

  // سجل نموذجي لكل مجموعة (نص البحث بالحرف الأصلي + الرابط + التطبيق)
  const samples = keys.length
    ? await db.event.findMany({
        where: { ...where, groupKey: { in: keys } },
        orderBy: { occurredAt: "desc" },
        select: {
          id: true, groupKey: true, type: true, title: true, url: true, app: true,
          packageName: true, phone: true, direction: true, durationSec: true,
          incognito: true, occurredAt: true, meta: true,
        },
      })
    : [];

  const sampleByKey = new Map<string, (typeof samples)[number]>();
  for (const s of samples) {
    if (s.groupKey && !sampleByKey.has(s.groupKey)) sampleByKey.set(s.groupKey, s);
  }

  const groups = page.map((g) => {
    const s = g.groupKey ? sampleByKey.get(g.groupKey) : undefined;
    return {
      groupKey: g.groupKey,
      count: g._count._all,
      lastAt: g._max.occurredAt,
      sample: s ?? null,
    };
  });

  const total = distinctRows.length;

  return NextResponse.json({
    groups,
    total,
    offset,
    hasMore: offset + groups.length < total,
  });
}

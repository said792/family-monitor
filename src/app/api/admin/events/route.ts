import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { verifySessionToken, SESSION_COOKIE } from "@/lib/auth";

const TYPES = ["SEARCH", "BROWSER", "CALL", "APP", "WHATSAPP"] as const;

export async function GET(req: NextRequest) {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  if (!(await verifySessionToken(token))) {
    return NextResponse.json({ error: "غير مصرح" }, { status: 401 });
  }

  const sp = req.nextUrl.searchParams;
  const deviceId = sp.get("deviceId") || undefined;
  const type = sp.get("type");
  const q = sp.get("q")?.trim();
  const limit = Math.min(Number(sp.get("limit")) || 100, 500);

  const where: {
    deviceId?: string;
    type?: string;
    OR?: { title?: { contains: string }; url?: { contains: string }; app?: { contains: string }; phone?: { contains: string }; packageName?: { contains: string }; meta?: { contains: string } }[];
  } = {};
  if (deviceId) where.deviceId = deviceId;
  if (type && (TYPES as readonly string[]).includes(type)) where.type = type;
  if (q) {
    where.OR = [
      { title: { contains: q } },
      { url: { contains: q } },
      { app: { contains: q } },
      { phone: { contains: q } },
      { packageName: { contains: q } },
      { meta: { contains: q } },
    ];
  }

  const events = await db.event.findMany({
    where,
    orderBy: { occurredAt: "desc" },
    take: limit,
    include: { device: { select: { name: true, platform: true } } },
  });

  return NextResponse.json({ events });
}

interface DeleteTarget {
  deviceId: string;
  type?: string;
  title?: string;
  phone?: string;
  app?: string;
  packageName?: string;
  urlPrefix?: string;
}

// حذف السجلات (الجهاز الأساسي فقط). الأنماط المدعومة:
//  - { ids: [...] }                       حذف أحداث محددة
//  - { targets: [...] }                   حذف محادثة/سلسلة كاملة (حتى الأحدث من الحد المعروض)
//  - { deviceId, all: true }              تفريغ سجل جهاز بالكامل (بدون حذف الجهاز)
//  - { olderThanDays: N }                 حذف كل الأحداث الأقدم من N يومًا (كل الأجهزة)
export async function DELETE(req: NextRequest) {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  if (!(await verifySessionToken(token))) {
    return NextResponse.json({ error: "غير مصرح" }, { status: 401 });
  }

  const body = (await req.json().catch(() => null)) as
    | { ids?: string[]; targets?: DeleteTarget[]; deviceId?: string; all?: boolean; olderThanDays?: number }
    | null;
  if (!body) {
    return NextResponse.json({ error: "طلب غير صالح" }, { status: 400 });
  }

  const or: Record<string, unknown>[] = [];

  if (Array.isArray(body.ids) && body.ids.length > 0) {
    or.push({ id: { in: body.ids.slice(0, 1000) } });
  }

  for (const t of Array.isArray(body.targets) ? body.targets : []) {
    if (!t?.deviceId) continue;
    const w: Record<string, unknown> = { deviceId: t.deviceId };
    if (t.type && (TYPES as readonly string[]).includes(t.type)) w.type = t.type;
    if (t.title) w.title = t.title;
    if (t.phone) w.phone = t.phone;
    if (t.app) w.app = t.app;
    if (t.packageName) w.packageName = t.packageName;
    if (t.urlPrefix) w.url = { startsWith: t.urlPrefix };
    or.push(w);
  }

  let where: Record<string, unknown> | null = null;
  if (body.all && body.deviceId) {
    where = { deviceId: body.deviceId };
  } else if (body.olderThanDays && Number(body.olderThanDays) > 0) {
    where = { occurredAt: { lt: new Date(Date.now() - Number(body.olderThanDays) * 86_400_000) } };
  } else if (or.length > 0) {
    where = { OR: or };
  } else {
    return NextResponse.json({ error: "لا يوجد ما يحدد السجلات المطلوب حذفها" }, { status: 400 });
  }

  const r = await db.event.deleteMany({ where: where as never });
  return NextResponse.json({ deleted: r.count });
}

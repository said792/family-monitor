import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { verifySessionToken, SESSION_COOKIE } from "@/lib/auth";
import { EVENT_TYPES } from "@/lib/event-types";

export async function GET(req: NextRequest) {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  if (!(await verifySessionToken(token))) {
    return NextResponse.json({ error: "غير مصرح" }, { status: 401 });
  }

  const sp = req.nextUrl.searchParams;
  const deviceId = sp.get("deviceId") || undefined;
  const q = sp.get("q")?.trim().slice(0, 200);
  const requestedTypes = (sp.get("types") || sp.get("type") || "")
    .split(",").map((value) => value.trim().toUpperCase()).filter((value) => (EVENT_TYPES as readonly string[]).includes(value));
  const limit = Math.min(Math.max(Number(sp.get("limit")) || 200, 1), 200);
  const rawOffset = Number(sp.get("offset"));
  const offset = Number.isFinite(rawOffset) ? Math.min(Math.max(Math.trunc(rawOffset), 0), 10_000_000) : 0;
  const fromValue = sp.get("from");
  const toValue = sp.get("to");
  const from = fromValue ? new Date(fromValue) : null;
  const to = toValue ? new Date(toValue) : null;
  if ((from && isNaN(from.getTime())) || (to && isNaN(to.getTime()))) {
    return NextResponse.json({ error: "صيغة التاريخ غير صحيحة" }, { status: 400 });
  }
  if (from && to && from > to) {
    return NextResponse.json({ error: "تاريخ البداية يجب أن يسبق تاريخ النهاية" }, { status: 400 });
  }

  const packageNames = (sp.get("packages") || "").split(",").map(v => v.trim()).filter(Boolean);
  const excludedPackages = (sp.get("excludePackages") || "").split(",").map(v => v.trim()).filter(Boolean);
  const and: Record<string, unknown>[] = [];
  const where: Record<string, unknown> = {};
  if (deviceId) where.deviceId = deviceId;
  if (requestedTypes.length === 1) where.type = requestedTypes[0];
  else if (requestedTypes.length > 1) where.type = { in: requestedTypes };
  if (from || to) where.occurredAt = { ...(from ? { gte: from } : {}), ...(to ? { lte: to } : {}) };
  if (q) {
    and.push({ OR: [
      { title: { contains: q, mode: "insensitive" } },
      { url: { contains: q, mode: "insensitive" } },
      { app: { contains: q, mode: "insensitive" } },
      { phone: { contains: q, mode: "insensitive" } },
      { packageName: { contains: q, mode: "insensitive" } },
      { meta: { contains: q, mode: "insensitive" } },
    ] });
  }
  if (packageNames.length) and.push({ packageName: { in: packageNames } });
  if (excludedPackages.length) and.push({ OR: [ { packageName: null }, { packageName: { notIn: excludedPackages } } ] });
  if (and.length) where.AND = and;

  const [events, total] = await Promise.all([
    db.event.findMany({
      where: where as never,
      orderBy: [{ occurredAt: "desc" }, { createdAt: "desc" }, { id: "desc" }],
      skip: offset,
      take: limit,
      include: { device: { select: { name: true, platform: true } } },
    }),
    db.event.count({ where: where as never }),
  ]);

  return NextResponse.json({ events, total, limit, offset, hasMore: offset + events.length < total });
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
    if (t.type && (EVENT_TYPES as readonly string[]).includes(t.type)) w.type = t.type;
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

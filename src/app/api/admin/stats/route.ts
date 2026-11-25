import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { verifySessionToken, SESSION_COOKIE } from "@/lib/auth";

export async function GET(req: NextRequest) {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  if (!(await verifySessionToken(token))) {
    return NextResponse.json({ error: "غير مصرح" }, { status: 401 });
  }

  const now = new Date();
  const startOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate());

  const [devices, totalEvents, todayEvents, todaySearch, todayCalls, todayApps, todayWhatsapp] = await Promise.all([
    db.device.count(),
    db.event.count(),
    db.event.count({ where: { occurredAt: { gte: startOfDay } } }),
    db.event.count({ where: { type: { in: ["SEARCH"] }, occurredAt: { gte: startOfDay } } }),
    db.event.count({ where: { type: "CALL", occurredAt: { gte: startOfDay } } }),
    db.event.count({ where: { type: "APP", occurredAt: { gte: startOfDay } } }),
    db.event.count({ where: { type: "WHATSAPP", occurredAt: { gte: startOfDay } } }),
  ]);

  // آخر 12 ساعة: عدد الأحداث لكل ساعة (للرسم البياني)
  const since = new Date(now.getTime() - 12 * 60 * 60 * 1000);
  const recent = await db.event.findMany({
    where: { occurredAt: { gte: since } },
    select: { occurredAt: true },
  });
  const hourly: { hour: string; count: number }[] = [];
  for (let i = 11; i >= 0; i--) {
    const h = new Date(now.getTime() - i * 60 * 60 * 1000);
    hourly.push({
      hour: `${h.getHours()}:00`,
      count: recent.filter(
        (e) =>
          e.occurredAt.getHours() === h.getHours() &&
          e.occurredAt.getDate() === h.getDate()
      ).length,
    });
  }

  return NextResponse.json({
    devices,
    totalEvents,
    todayEvents,
    todaySearch,
    todayCalls,
    todayApps,
    todayWhatsapp,
    hourly,
  });
}

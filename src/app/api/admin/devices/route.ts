import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { verifySessionToken, SESSION_COOKIE } from "@/lib/auth";

async function requireAdmin(req: NextRequest) {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  if (!(await verifySessionToken(token))) return null;
  return true;
}

// قائمة الأجهزة مع إحصائيات مختصرة
export async function GET(req: NextRequest) {
  if (!(await requireAdmin(req))) {
    return NextResponse.json({ error: "غير مصرح" }, { status: 401 });
  }
  const devices = await db.device.findMany({
    orderBy: { createdAt: "desc" },
    include: {
      _count: { select: { events: true } },
      events: {
        orderBy: { occurredAt: "desc" },
        take: 1,
        select: { type: true, title: true, occurredAt: true },
      },
    },
  });

  const result = devices.map((d) => ({
    id: d.id,
    name: d.name,
    platform: d.platform,
    status: d.status,
    lastSeen: d.lastSeen,
    createdAt: d.createdAt,
    totalEvents: d._count.events,
    lastEvent: d.events[0] ?? null,
  }));
  return NextResponse.json({ devices: result });
}

// تعديل جهاز (تسمية / حظر / تنشيط)
export async function PATCH(req: NextRequest) {
  if (!(await requireAdmin(req))) {
    return NextResponse.json({ error: "غير مصرح" }, { status: 401 });
  }
  const body = await req.json().catch(() => null);
  const id = body?.id;
  if (!id) return NextResponse.json({ error: "معرف الجهاز مطلوب" }, { status: 400 });

  const data: { name?: string; status?: string } = {};
  if (typeof body.name === "string" && body.name.trim()) data.name = body.name.trim().slice(0, 60);
  if (body.status === "active" || body.status === "blocked") data.status = body.status;

  const device = await db.device.update({ where: { id }, data });
  return NextResponse.json({ ok: true, device });
}

// حذف جهاز وكل سجلاته
export async function DELETE(req: NextRequest) {
  if (!(await requireAdmin(req))) {
    return NextResponse.json({ error: "غير مصرح" }, { status: 401 });
  }
  const id = req.nextUrl.searchParams.get("id");
  if (!id) return NextResponse.json({ error: "معرف الجهاز مطلوب" }, { status: 400 });
  await db.device.delete({ where: { id } });
  return NextResponse.json({ ok: true });
}

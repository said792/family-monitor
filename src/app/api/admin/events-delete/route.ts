import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { verifySessionToken, SESSION_COOKIE } from "@/lib/auth";

/**
 * حذف سجلات — الجهاز الأساسي (الأدمن) فقط.
 * لا يوجد أي مسار حذف للأجهزة الفرعية إطلاقًا، ولا حذف تلقائي لأي سجل.
 * Body: { ids?: string[], deviceId?, type?, groupKey?, from/to? }
 */
export async function POST(req: NextRequest) {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  if (!(await verifySessionToken(token))) {
    return NextResponse.json({ error: "غير مصرح — الحذف للأدمن فقط" }, { status: 401 });
  }

  const body = await req.json().catch(() => null);
  const ids: string[] = Array.isArray(body?.ids)
    ? body.ids.filter((x: unknown) => typeof x === "string").slice(0, 2000)
    : [];
  const deviceId = typeof body?.deviceId === "string" ? body.deviceId : undefined;
  const type = typeof body?.type === "string" ? body.type : undefined;
  const groupKey = typeof body?.groupKey === "string" && body.groupKey.trim() ? body.groupKey.trim() : undefined;
  const from = typeof body?.from === "string" ? body.from : undefined;
  const to = typeof body?.to === "string" ? body.to : undefined;

  const where: Record<string, unknown> = {};
  if (ids.length > 0) {
    where.id = { in: ids };
  } else {
    if (deviceId) where.deviceId = deviceId;
    if (type) where.type = type;
    if (groupKey) where.groupKey = groupKey;
    if (from || to) {
      const occurredAt: Record<string, Date> = {};
      if (from && !isNaN(Date.parse(from))) occurredAt.gte = new Date(from);
      if (to && !isNaN(Date.parse(to))) occurredAt.lt = new Date(to);
      where.occurredAt = occurredAt;
    }
    if (Object.keys(where).length === 0) {
      return NextResponse.json(
        { error: "حدّد ما تريد حذفه (سجلات محددة أو مجموعة أو نطاق يوم)" },
        { status: 400 }
      );
    }
  }

  const deleted = await db.event.deleteMany({ where });

  return NextResponse.json({ ok: true, deleted: deleted.count });
}

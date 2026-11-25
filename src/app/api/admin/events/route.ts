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

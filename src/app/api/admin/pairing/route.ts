import { NextRequest, NextResponse } from "next/server";
import { randomInt } from "crypto";
import { db } from "@/lib/db";
import { verifySessionToken, SESSION_COOKIE } from "@/lib/auth";

// إنشاء كود اقتران جديد لتسجيل جهاز فرعي
export async function POST(req: NextRequest) {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  if (!(await verifySessionToken(token))) {
    return NextResponse.json({ error: "غير مصرح" }, { status: 401 });
  }

  // إلغاء الأكواد القديمة غير المستخدمة
  await db.pairingCode.deleteMany({ where: { used: false } });

  const code = String(randomInt(100000, 999999));
  const expiresAt = new Date(Date.now() + 10 * 60 * 1000); // صالح 10 دقائق
  const pairing = await db.pairingCode.create({ data: { code, expiresAt } });
  return NextResponse.json({ code: pairing.code, expiresAt: pairing.expiresAt });
}

// عرض الأكواد النشطة
export async function GET(req: NextRequest) {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  if (!(await verifySessionToken(token))) {
    return NextResponse.json({ error: "غير مصرح" }, { status: 401 });
  }
  const codes = await db.pairingCode.findMany({
    where: { used: false, expiresAt: { gt: new Date() } },
  });
  return NextResponse.json({ codes });
}

import { NextRequest, NextResponse } from "next/server";
import { verifyAdminPassword } from "@/lib/auth";

// حد بسيط لمحاولات التحقق (حماية من التجريب) — لكل عملية
const attempts = new Map<string, { count: number; resetAt: number }>();
const WINDOW_MS = 60_000;
const MAX_TRIES = 5;

function tooManyAttempts(ip: string): boolean {
  const now = Date.now();
  const a = attempts.get(ip);
  if (!a || a.resetAt < now) {
    attempts.set(ip, { count: 1, resetAt: now + WINDOW_MS });
    return false;
  }
  a.count++;
  return a.count > MAX_TRIES;
}

/**
 * التحقق من كلمة مرور الأدمن — يُستخدم على الجهاز الفرعي لفتح الإعدادات
 * بعد اختفاء التطبيق (شاشة التمويه): الزر الوحيد يطلب كلمة المرور.
 * لا يمنح أي جلسة — فقط «صحيح/خطأ».
 * Body: { password }
 */
export async function POST(req: NextRequest) {
  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
  if (tooManyAttempts(ip)) {
    return NextResponse.json({ error: "محاولات كثيرة — انتظر دقيقة" }, { status: 429 });
  }

  const body = await req.json().catch(() => null);
  const password = typeof body?.password === "string" ? body.password : "";
  if (!password) {
    return NextResponse.json({ ok: false }, { status: 400 });
  }

  const ok = await verifyAdminPassword(password);
  return NextResponse.json({ ok });
}

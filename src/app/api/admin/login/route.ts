import { NextRequest, NextResponse } from "next/server";
import {
  SESSION_COOKIE,
  createSessionToken,
  ensureSettings,
  isDefaultPassword,
  verifyAdminPassword,
} from "@/lib/auth";

export async function POST(req: NextRequest) {
  try {
    await ensureSettings();
    const body = await req.json().catch(() => null);
    const password = typeof body?.password === "string" ? body.password : "";
    if (!password) {
      return NextResponse.json({ error: "أدخل كلمة المرور" }, { status: 400 });
    }
    const ok = await verifyAdminPassword(password);
    if (!ok) {
      return NextResponse.json({ error: "كلمة المرور غير صحيحة" }, { status: 401 });
    }
    const { value, maxAge } = await createSessionToken();
    const res = NextResponse.json({
      ok: true,
      defaultPassword: await isDefaultPassword(),
    });
    res.cookies.set(SESSION_COOKIE, value, {
      httpOnly: true,
      sameSite: "lax",
      maxAge,
      path: "/",
    });
    return res;
  } catch (e) {
    console.error("login error", e);
    return NextResponse.json({ error: "حدث خطأ غير متوقع" }, { status: 500 });
  }
}

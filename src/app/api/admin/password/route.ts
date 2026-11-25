import { NextRequest, NextResponse } from "next/server";
import { changeAdminPassword, verifySessionToken, SESSION_COOKIE, isDefaultPassword } from "@/lib/auth";

export async function POST(req: NextRequest) {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  if (!(await verifySessionToken(token))) {
    return NextResponse.json({ error: "غير مصرح" }, { status: 401 });
  }
  const body = await req.json().catch(() => null);
  const newPassword = typeof body?.newPassword === "string" ? body.newPassword : "";
  if (newPassword.length < 6) {
    return NextResponse.json({ error: "كلمة المرور يجب أن تكون 6 أحرف على الأقل" }, { status: 400 });
  }
  await changeAdminPassword(newPassword);
  return NextResponse.json({ ok: true, defaultPassword: await isDefaultPassword() });
}

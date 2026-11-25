import { NextRequest, NextResponse } from "next/server";
import { randomBytes } from "crypto";
import { db } from "@/lib/db";
import { ensureSettings } from "@/lib/auth";

/**
 * تسجيل جهاز فرعي جديد بكود الاقتران.
 * Body: { pairingCode, name, platform }
 * Response: { deviceId, apiKey }
 */
export async function POST(req: NextRequest) {
  try {
    await ensureSettings();
    const body = await req.json().catch(() => null);
    const code = typeof body?.pairingCode === "string" ? body.pairingCode.trim() : "";
    const name = typeof body?.name === "string" ? body.name.trim().slice(0, 60) : "";
    const platform = typeof body?.platform === "string" ? body.platform.toLowerCase() : "";
    const providedKey = typeof body?.apiKey === "string" ? body.apiKey : "";

    if (!code) return NextResponse.json({ error: "كود الاقتران مطلوب" }, { status: 400 });
    if (!name) return NextResponse.json({ error: "اسم الجهاز مطلوب" }, { status: 400 });
    if (!["windows", "android", "linux", "macos"].includes(platform)) {
      return NextResponse.json({ error: "نوع النظام غير مدعوم" }, { status: 400 });
    }

    const pairing = await db.pairingCode.findUnique({ where: { code } });
    if (!pairing || pairing.used || pairing.expiresAt < new Date()) {
      return NextResponse.json({ error: "كود الاقتران غير صالح أو منتهي" }, { status: 401 });
    }

    const apiKey = providedKey || randomBytes(24).toString("hex");
    const device = await db.device.create({
      data: { name, platform, apiKey },
    });
    await db.pairingCode.update({ where: { id: pairing.id }, data: { used: true } });

    return NextResponse.json({ deviceId: device.id, apiKey });
  } catch (e) {
    console.error("register error", e);
    return NextResponse.json({ error: "حدث خطأ أثناء التسجيل" }, { status: 500 });
  }
}

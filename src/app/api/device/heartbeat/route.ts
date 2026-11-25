import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { authenticateDevice } from "@/lib/auth";

// نبضة اتصال دورية من الجهاز الفرعي لتحديث وقت آخر ظهور
export async function POST(req: NextRequest) {
  const device = await authenticateDevice(req);
  if (!device) {
    return NextResponse.json({ error: "مفتاح الجهاز غير صالح" }, { status: 401 });
  }
  await db.device.update({
    where: { id: device.id },
    data: { lastSeen: new Date() },
  });
  return NextResponse.json({ ok: true, status: device.status });
}

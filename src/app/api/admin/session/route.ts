import { NextRequest, NextResponse } from "next/server";
import { ensureSettings, isDefaultPassword, verifySessionToken, SESSION_COOKIE } from "@/lib/auth";

export async function GET(req: NextRequest) {
  await ensureSettings();
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  const authenticated = await verifySessionToken(token);
  return NextResponse.json({
    authenticated,
    defaultPassword: authenticated ? await isDefaultPassword() : false,
  });
}

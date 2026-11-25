// أنواع البيانات ودوال الاتصال بالـ API (جهة العميل)

export type EventType = "SEARCH" | "BROWSER" | "CALL" | "APP" | "WHATSAPP";

export interface DeviceInfo {
  id: string;
  name: string;
  platform: string;
  status: string;
  lastSeen: string | null;
  createdAt: string;
  totalEvents: number;
  lastEvent: { type: string; title: string | null; occurredAt: string } | null;
}

export interface EventRow {
  id: string;
  deviceId: string;
  type: EventType;
  title: string | null;
  url: string | null;
  app: string | null;
  packageName: string | null;
  direction: string | null;
  phone: string | null;
  durationSec: number | null;
  incognito: boolean;
  occurredAt: string;
  meta: string | null;
  device: { name: string; platform: string };
}

export interface Stats {
  devices: number;
  totalEvents: number;
  todayEvents: number;
  todaySearch: number;
  todayCalls: number;
  todayApps: number;
  todayWhatsapp: number;
  hourly: { hour: string; count: number }[];
}

export async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error((body as { error?: string }).error ?? "خطأ في الاتصال بالخادم");
  }
  return res.json() as Promise<T>;
}

// ---------- تنسيق الوقت (بتوقيت الجهاز الأساسي) ----------

export function formatClock(iso: string): string {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

export function formatDateTime(iso: string): string {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getDate())}/${p(d.getMonth() + 1)} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function timeAgo(iso: string | null): string {
  if (!iso) return "لم يتصل بعد";
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return "الآن";
  if (mins < 60) return `منذ ${mins} دقيقة`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `منذ ${hours} ساعة`;
  const days = Math.floor(hours / 24);
  return `منذ ${days} يوم`;
}

export function isOnline(lastSeen: string | null): boolean {
  if (!lastSeen) return false;
  return Date.now() - new Date(lastSeen).getTime() < 120_000;
}

export function formatDuration(sec: number | null): string {
  if (sec == null) return "";
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  if (m === 0) return `${s} ثانية`;
  return `${m} د ${s} ث`;
}

export function prettyUrl(url: string): string {
  try {
    const u = new URL(url);
    return u.host.replace(/^www\./, "") + (u.pathname === "/" ? "" : u.pathname.slice(0, 40));
  } catch {
    return url.slice(0, 50);
  }
}

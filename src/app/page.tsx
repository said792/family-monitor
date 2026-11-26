"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ShieldCheck, LogOut, Settings, Loader2, AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LoginView } from "@/components/dashboard/login-view";
import { StatsCards } from "@/components/dashboard/stats-cards";
import { DevicesPanel } from "@/components/dashboard/devices-panel";
import { EventsFeed } from "@/components/dashboard/events-feed";
import { SettingsDialog } from "@/components/dashboard/settings-dialog";
import { api, type DeviceInfo, type EventRow, type Stats } from "@/lib/client";

const WA_PKGS = new Set([
  "com.whatsapp", "com.whatsapp.w4b", "com.gbwhatsapp", "com.gbwhatsm",
  "com.fmwhatsapp", "com.yowhatsapp", "com.whatsappplus", "com.wplus",
]);
const FB_PKGS = new Set([
  "com.facebook.katana", "com.facebook.lite", "com.facebook.orca", "com.facebook.mlite",
]);
const SOCIAL_PKGS = new Set([...WA_PKGS, ...FB_PKGS]);

export default function Page() {
  const [loadingSession, setLoadingSession] = useState(true);
  const [authed, setAuthed] = useState(false);
  const [defaultPassword, setDefaultPassword] = useState(false);
  const [showSettings, setShowSettings] = useState(false);

  const [stats, setStats] = useState<Stats | null>(null);
  const [devices, setDevices] = useState<DeviceInfo[]>([]);
  const [events, setEvents] = useState<EventRow[]>([]);

  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);
  const [typeFilter, setTypeFilter] = useState("ALL");
  const [query, setQuery] = useState("");
  const [live, setLive] = useState(true);
  const [loadingFeed, setLoadingFeed] = useState(false);
  const feedOffsetRef = useRef(0);
  const [feedHasMore, setFeedHasMore] = useState(false);
  const [feedTotal, setFeedTotal] = useState(0);
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");

  const queryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [debouncedQuery, setDebouncedQuery] = useState("");

  // أثناء مرور المؤشر فوق قائمة السجلات نوقف التحديث المباشر مؤقتًا
  // حتى لا تختفي الرابط/التفاصيل التي يشير إليها المؤشر
  const feedHoverRef = useRef(false);

  // تحقق من الجلسة عند التحميل
  useEffect(() => {
    api<{ authenticated: boolean; defaultPassword: boolean }>("/api/admin/session")
      .then((r) => {
        setAuthed(r.authenticated);
        setDefaultPassword(r.defaultPassword);
      })
      .catch(() => setAuthed(false))
      .finally(() => setLoadingSession(false));
  }, []);

  // البحث مع مهلة قصيرة
  useEffect(() => {
    if (queryTimer.current) clearTimeout(queryTimer.current);
    queryTimer.current = setTimeout(() => setDebouncedQuery(query), 400);
    return () => {
      if (queryTimer.current) clearTimeout(queryTimer.current);
    };
  }, [query]);

  const refreshFeed = useCallback(async (append = false) => {
    setLoadingFeed(true);
    try {
      const params = new URLSearchParams();
      if (selectedDeviceId) params.set("deviceId", selectedDeviceId);
      if (debouncedQuery) params.set("q", debouncedQuery);
      if (dateFrom) params.set("from", new Date(`${dateFrom}T00:00:00`).toISOString());
      if (dateTo) params.set("to", new Date(`${dateTo}T23:59:59.999`).toISOString());
      params.set("limit", "200");
      params.set("offset", String(append ? feedOffsetRef.current : 0));

      const typeMap: Record<string, string> = {
        SEARCH_BROWSER: "SEARCH,BROWSER",
        FACEBOOK: "MEDIA,APP",
      };
      if (typeFilter !== "ALL") params.set("types", typeMap[typeFilter] ?? typeFilter);
      if (typeFilter === "APP") params.set("excludePackages", [...SOCIAL_PKGS].join(","));
      if (typeFilter === "MEDIA") params.set("excludePackages", [...FB_PKGS].join(","));
      if (typeFilter === "FACEBOOK") params.set("packages", [...FB_PKGS].join(","));

      const r = await api<{ events: EventRow[]; total: number; hasMore: boolean; limit: number; offset: number }>(`/api/admin/events?${params}`);
      setEvents((current) => {
        if (!append) return r.events;
        const seen = new Set(current.map((event) => event.id));
        return [...current, ...r.events.filter((event) => !seen.has(event.id))];
      });
      feedOffsetRef.current = r.offset + r.events.length;
      setFeedHasMore(r.hasMore);
      setFeedTotal(r.total);
    } catch {
      /* يبقى المحتوى الحالي ظاهرًا عند تعذر التحديث */
    } finally {
      setLoadingFeed(false);
    }
  }, [selectedDeviceId, typeFilter, debouncedQuery, dateFrom, dateTo]);

  const refreshAll = useCallback(async () => {
    try {
      const [s, d] = await Promise.all([
        api<Stats>("/api/admin/stats"),
        api<{ devices: DeviceInfo[] }>("/api/admin/devices"),
      ]);
      setStats(s);
      setDevices(d.devices);
    } catch {
      /* تجاهل */
    }
  }, []);

  // تحديث عند تغيير الفلاتر
  useEffect(() => {
    if (!authed) return;
    refreshFeed(false);
  }, [authed, refreshFeed]);

  // التحديث المباشر كل 5 ثوانٍ — يتوقف مؤقتًا أثناء مرور المؤشر فوق القائمة
  useEffect(() => {
    if (!authed) return;
    refreshAll();
    const id = setInterval(() => {
      if (feedHoverRef.current) return;
      refreshAll();
      if (live) refreshFeed(false);
    }, 5000);
    return () => clearInterval(id);
  }, [authed, live, refreshAll, refreshFeed]);

  async function logout() {
    await api("/api/admin/logout", { method: "POST" }).catch(() => {});
    setAuthed(false);
  }

  if (loadingSession) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-950">
        <Loader2 className="w-8 h-8 animate-spin text-emerald-400" />
      </div>
    );
  }

  if (!authed) {
    return (
      <LoginView
        onSuccess={(isDefault) => {
          setAuthed(true);
          setDefaultPassword(isDefault);
        }}
      />
    );
  }

  return (
    <div className="min-h-screen bg-slate-100 flex flex-col" dir="rtl">
      {/* الشريط العلوي */}
      <header className="bg-slate-900 text-white sticky top-0 z-40 shadow-lg">
        <div className="max-w-7xl mx-auto px-4 py-3 flex items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-emerald-600/20 border border-emerald-500/30 flex items-center justify-center">
              <ShieldCheck className="w-5 h-5 text-emerald-400" />
            </div>
            <div>
              <h1 className="font-bold text-sm sm:text-base">مركز الرقابة الأسرية</h1>
              <p className="text-[11px] text-slate-400 hidden sm:block">
                الجهاز الأساسي فقط يملك صلاحية عرض السجلات
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <span
              className="text-[10px] text-emerald-300 bg-slate-800 border border-slate-700 rounded-full px-2 py-1 hidden sm:inline"
              title="إصدار كود اللوحة — لو مش ظاهرة يبقى الكود لم يُرفع على GitHub/Vercel بعد"
            >
              اللوحة v2.5.2
            </span>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setShowSettings(true)}
              className="text-slate-200 hover:text-white hover:bg-slate-800 gap-1.5"
            >
              <Settings className="w-4 h-4" />
              <span className="hidden sm:inline">الإعدادات</span>
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={logout}
              className="text-slate-200 hover:text-white hover:bg-slate-800 gap-1.5"
            >
              <LogOut className="w-4 h-4" />
              <span className="hidden sm:inline">خروج</span>
            </Button>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto w-full px-4 py-6 space-y-5 flex-1">
        {defaultPassword && (
          <div className="flex items-center gap-3 rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-amber-900">
            <AlertTriangle className="w-5 h-5 shrink-0" />
            <p className="text-sm flex-1">
              ما زلت تستخدم كلمة المرور المبدئية — غيّرها فورًا من الإعدادات لحماية السجلات.
            </p>
            <Button size="sm" onClick={() => setShowSettings(true)} className="bg-amber-600 hover:bg-amber-500 shrink-0">
              تغيير الآن
            </Button>
          </div>
        )}

        <StatsCards stats={stats} />

        <div className="grid lg:grid-cols-[320px_1fr] gap-5 items-start">
          <DevicesPanel
            devices={devices}
            selectedId={selectedDeviceId}
            onSelect={setSelectedDeviceId}
          />
          <EventsFeed
            events={events}
            devices={devices}
            typeFilter={typeFilter}
            onTypeFilter={setTypeFilter}
            query={query}
            onQuery={setQuery}
            live={live}
            onLive={setLive}
            loading={loadingFeed}
            onRefresh={() => refreshFeed(false)}
            onLoadMore={() => refreshFeed(true)}
            hasMore={feedHasMore}
            total={feedTotal}
            dateFrom={dateFrom}
            dateTo={dateTo}
            onDateFrom={setDateFrom}
            onDateTo={setDateTo}
            onHoverChange={(h) => {
              feedHoverRef.current = h;
            }}
          />
        </div>
      </main>

      <footer className="bg-slate-900 text-slate-400 mt-auto">
        <div className="max-w-7xl mx-auto px-4 py-3 text-center text-[11px] leading-relaxed">
          نظام مراقبة أجهزة — يُستخدم لأغراض مشروعة فقط (رقابة أولياء الأمور على الأطفال / أجهزة الشركة
          بمعرفة العاملين). جميع السجلات تُخزَّن مركزيًا ولا يمكن لأي جهاز فرعي الاطلاع عليها.
        </div>
      </footer>

      <SettingsDialog
        open={showSettings}
        onOpenChange={setShowSettings}
        devices={devices}
        onChanged={() => {
          refreshAll();
          refreshFeed(false);
        }}
      />
    </div>
  );
}

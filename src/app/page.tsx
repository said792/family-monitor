"use client";

import { useCallback, useEffect, useState } from "react";
import { ShieldCheck, LogOut, Settings, Loader2, AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LoginView } from "@/components/dashboard/login-view";
import { StatsCards } from "@/components/dashboard/stats-cards";
import { DevicesPanel } from "@/components/dashboard/devices-panel";
import { EventsFeed } from "@/components/dashboard/events-feed";
import { SettingsDialog } from "@/components/dashboard/settings-dialog";
import { api, type DeviceInfo, type Stats } from "@/lib/client";

export default function Page() {
  const [loadingSession, setLoadingSession] = useState(true);
  const [authed, setAuthed] = useState(false);
  const [defaultPassword, setDefaultPassword] = useState(false);
  const [showSettings, setShowSettings] = useState(false);

  const [stats, setStats] = useState<Stats | null>(null);
  const [devices, setDevices] = useState<DeviceInfo[]>([]);

  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);
  const [typeFilter, setTypeFilter] = useState("SEARCH_BROWSER");
  const [query, setQuery] = useState("");
  const [live, setLive] = useState(true);

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

  // تحديث الإحصائيات والأجهزة دوريًا
  useEffect(() => {
    if (!authed) return;
    refreshAll();
    const id = setInterval(() => refreshAll(), 5000);
    return () => clearInterval(id);
  }, [authed, refreshAll]);

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
            deviceId={selectedDeviceId}
            typeFilter={typeFilter}
            onTypeFilter={setTypeFilter}
            query={query}
            onQuery={setQuery}
            live={live}
            onLive={setLive}
            onChanged={refreshAll}
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
        onChanged={refreshAll}
      />
    </div>
  );
}

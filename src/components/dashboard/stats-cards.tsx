"use client";

import { Card, CardContent } from "@/components/ui/card";
import { Monitor, Activity, Search, Phone, Grid3X3, MessageCircle } from "lucide-react";
import type { Stats } from "@/lib/client";

function StatCard({
  icon: Icon,
  label,
  value,
  accent,
}: {
  icon: typeof Search;
  label: string;
  value: number | string;
  accent: string;
}) {
  return (
    <Card className="border-slate-200">
      <CardContent className="p-4 flex items-center gap-3">
        <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${accent}`}>
          <Icon className="w-5 h-5" />
        </div>
        <div className="min-w-0">
          <p className="text-xs text-slate-500">{label}</p>
          <p className="text-xl font-bold text-slate-900 tabular-nums">{value}</p>
        </div>
      </CardContent>
    </Card>
  );
}

export function StatsCards({ stats }: { stats: Stats | null }) {
  const maxCount = Math.max(1, ...(stats?.hourly.map((h) => h.count) ?? [1]));

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
        <StatCard
          icon={Monitor}
          label="الأجهزة المراقبة"
          value={stats?.devices ?? "—"}
          accent="bg-emerald-100 text-emerald-700"
        />
        <StatCard
          icon={Activity}
          label="أحداث اليوم"
          value={stats?.todayEvents ?? "—"}
          accent="bg-teal-100 text-teal-700"
        />
        <StatCard
          icon={Search}
          label="عمليات بحث اليوم"
          value={stats?.todaySearch ?? "—"}
          accent="bg-amber-100 text-amber-700"
        />
        <StatCard
          icon={Phone}
          label="مكالمات اليوم"
          value={stats?.todayCalls ?? "—"}
          accent="bg-rose-100 text-rose-700"
        />
        <StatCard
          icon={Grid3X3}
          label="فتح تطبيقات اليوم"
          value={stats?.todayApps ?? "—"}
          accent="bg-violet-100 text-violet-700"
        />
        <StatCard
          icon={MessageCircle}
          label="رسائل واتساب اليوم"
          value={stats?.todayWhatsapp ?? "—"}
          accent="bg-emerald-100 text-emerald-700"
        />
      </div>

      {/* نشاط آخر 12 ساعة */}
      <Card className="border-slate-200">
        <CardContent className="p-4">
          <p className="text-xs text-slate-500 mb-3">نشاط الأجهزة — آخر 12 ساعة</p>
          <div dir="ltr" className="flex items-end gap-1 h-20">
            {(stats?.hourly ?? Array(12).fill({ hour: "", count: 0 })).map((h, i) => (
              <div key={i} className="flex-1 flex flex-col items-center gap-1 group">
                <div className="text-[10px] text-slate-400 opacity-0 group-hover:opacity-100 transition-opacity tabular-nums">
                  {h.count}
                </div>
                <div
                  className="w-full rounded-t bg-emerald-500/80 hover:bg-emerald-500 transition-all"
                  style={{ height: `${Math.max(4, (h.count / maxCount) * 56)}px` }}
                />
                <div className="text-[9px] text-slate-400 tabular-nums">{h.hour}</div>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

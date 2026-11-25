"use client";

import { Monitor, Smartphone, Wifi, WifiOff, Ban, ChevronLeft } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { isOnline, timeAgo, type DeviceInfo } from "@/lib/client";
import { cn } from "@/lib/utils";

function PlatformIcon({ platform, className }: { platform: string; className?: string }) {
  if (platform === "android") return <Smartphone className={className} />;
  return <Monitor className={className} />;
}

export function DevicesPanel({
  devices,
  selectedId,
  onSelect,
}: {
  devices: DeviceInfo[];
  selectedId: string | null;
  onSelect: (id: string | null) => void;
}) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white overflow-hidden flex flex-col">
      <div className="px-4 py-3 border-b border-slate-100 flex items-center justify-between">
        <h3 className="font-semibold text-slate-800 text-sm">الأجهزة الفرعية ({devices.length})</h3>
        {selectedId && (
          <Button variant="ghost" size="sm" onClick={() => onSelect(null)} className="text-xs h-7">
            إظهار الكل
          </Button>
        )}
      </div>
      <ScrollArea className="max-h-[420px] lg:max-h-[520px]">
        {devices.length === 0 ? (
          <div className="p-6 text-center text-sm text-slate-500 leading-relaxed">
            لا توجد أجهزة مسجلة بعد.
            <br />
            من الإعدادات أنشئ كود اقتران ثم شغّل برنامج المراقبة على الجهاز الفرعي.
          </div>
        ) : (
          <div className="divide-y divide-slate-100">
            {devices.map((d) => {
              const online = isOnline(d.lastSeen);
              return (
                <button
                  key={d.id}
                  onClick={() => onSelect(d.id === selectedId ? null : d.id)}
                  className={cn(
                    "w-full text-right px-4 py-3 flex items-center gap-3 hover:bg-emerald-50/60 transition-colors",
                    d.id === selectedId && "bg-emerald-50"
                  )}
                >
                  <div
                    className={cn(
                      "w-10 h-10 rounded-xl flex items-center justify-center shrink-0",
                      d.status === "blocked"
                        ? "bg-slate-100 text-slate-400"
                        : online
                          ? "bg-emerald-100 text-emerald-700"
                          : "bg-amber-100 text-amber-700"
                    )}
                  >
                    <PlatformIcon platform={d.platform} className="w-5 h-5" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <p className="font-medium text-slate-800 text-sm truncate">{d.name}</p>
                      {d.status === "blocked" && (
                        <Badge variant="secondary" className="bg-slate-200 text-slate-600 text-[10px] gap-1">
                          <Ban className="w-3 h-3" /> محظور
                        </Badge>
                      )}
                    </div>
                    <p className="text-xs text-slate-500 flex items-center gap-1 mt-0.5">
                      {online ? (
                        <Wifi className="w-3 h-3 text-emerald-500" />
                      ) : (
                        <WifiOff className="w-3 h-3 text-slate-400" />
                      )}
                      {online ? "متصل الآن" : timeAgo(d.lastSeen)} · {d.totalEvents} حدث
                    </p>
                    {d.lastEvent && (
                      <p className="text-[11px] text-slate-400 truncate mt-0.5">
                        آخر حدث: {d.lastEvent.title ?? d.lastEvent.type}
                      </p>
                    )}
                  </div>
                  <ChevronLeft className="w-4 h-4 text-slate-300 shrink-0" />
                </button>
              );
            })}
          </div>
        )}
      </ScrollArea>
    </div>
  );
}

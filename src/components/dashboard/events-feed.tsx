"use client";

import { Search, Globe, Phone, Grid3X3, EyeOff, ArrowDownLeft, ArrowUpRight, PhoneMissed, Package, MessageCircle, Loader2, RefreshCw } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Switch } from "@/components/ui/switch";
import { Label } from "@/components/ui/label";
import {
  formatClock,
  formatDateTime,
  formatDuration,
  prettyUrl,
  type EventRow,
} from "@/lib/client";
import { cn } from "@/lib/utils";

const TYPE_LABELS: Record<string, string> = {
  SEARCH: "بحث",
  BROWSER: "تصفح",
  CALL: "مكالمة",
  APP: "تطبيق",
  WHATSAPP: "واتساب",
};

const TABS = [
  { value: "ALL", label: "الكل" },
  { value: "SEARCH_BROWSER", label: "بحث وتصفح" },
  { value: "CALL", label: "مكالمات" },
  { value: "APP", label: "تطبيقات" },
  { value: "WHATSAPP", label: "واتساب" },
];

function EventIcon({ ev }: { ev: EventRow }) {
  if (ev.type === "CALL") {
    if (ev.direction === "MISSED") return <PhoneMissed className="w-4 h-4" />;
    if (ev.direction === "INCOMING") return <ArrowDownLeft className="w-4 h-4" />;
    return <ArrowUpRight className="w-4 h-4" />;
  }
  if (ev.type === "APP") return <Grid3X3 className="w-4 h-4" />;
  if (ev.type === "WHATSAPP") return <MessageCircle className="w-4 h-4" />;
  if (ev.type === "SEARCH") return <Search className="w-4 h-4" />;
  return <Globe className="w-4 h-4" />;
}

const ICON_STYLES: Record<string, string> = {
  SEARCH: "bg-amber-100 text-amber-700",
  BROWSER: "bg-sky-100 text-sky-700",
  CALL: "bg-rose-100 text-rose-700",
  APP: "bg-violet-100 text-violet-700",
  WHATSAPP: "bg-emerald-100 text-emerald-700",
};

/** قراءة نص الرسالة واسم المرسل من حقل meta لأحداث واتساب */
function whatsMeta(meta: string | null): { text?: string; sender?: string; group?: boolean } {
  if (!meta) return {};
  try {
    const o = JSON.parse(meta) as Record<string, unknown>;
    return {
      text: typeof o.text === "string" ? o.text : undefined,
      sender: typeof o.sender === "string" ? o.sender : undefined,
      group: o.group === true,
    };
  } catch {
    return {};
  }
}

/** السطر الثاني من كل صف: تفاصيل الحدث */
function detailText(ev: EventRow): string {
  if (ev.type === "CALL") {
    const dir =
      ev.direction === "INCOMING" ? "مكالمة واردة" : ev.direction === "MISSED" ? "مكالمة فائتة" : "مكالمة صادرة";
    return `${dir}${ev.app ? ` — ${ev.app}` : ""}`;
  }
  if (ev.type === "WHATSAPP") {
    const m = whatsMeta(ev.meta);
    const body = m.text ?? "";
    // رسالة مجموعة: نعرض اسم المرسل داخل المجموعة قبل النص
    if (m.group && m.sender && ev.title && m.sender !== ev.title) return `${m.sender}: ${body}`;
    return body;
  }
  return ev.url ? prettyUrl(ev.url) : ev.packageName ?? ev.app ?? "";
}

export function EventsFeed({
  events,
  typeFilter,
  onTypeFilter,
  query,
  onQuery,
  live,
  onLive,
  loading,
  onRefresh,
  onHoverChange,
}: {
  events: EventRow[];
  typeFilter: string;
  onTypeFilter: (t: string) => void;
  query: string;
  onQuery: (q: string) => void;
  live: boolean;
  onLive: (v: boolean) => void;
  loading: boolean;
  onRefresh: () => void;
  onHoverChange?: (hovering: boolean) => void;
}) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white overflow-hidden flex flex-col">
      <div className="px-4 pt-3 border-b border-slate-100 space-y-3">
        <div className="flex items-center justify-between gap-2 flex-wrap">
          <div className="flex items-center gap-2">
            <Tabs value={typeFilter} onValueChange={onTypeFilter}>
              <TabsList className="h-8 bg-slate-100">
                {TABS.map((t) => (
                  <TabsTrigger key={t.value} value={t.value} className="text-xs h-7 px-3">
                    {t.label}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>
          </div>
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-2">
              <Switch id="live" checked={live} onCheckedChange={onLive} />
              <Label htmlFor="live" className="text-xs text-slate-600 flex items-center gap-1 cursor-pointer">
                <span className={cn("w-2 h-2 rounded-full", live ? "bg-emerald-500 animate-pulse" : "bg-slate-300")} />
                تحديث مباشر
              </Label>
            </div>
            <Button variant="outline" size="sm" onClick={onRefresh} className="h-8 gap-1 text-xs">
              {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
              تحديث
            </Button>
          </div>
        </div>
        <div className="pb-3 relative">
          <Search className="absolute right-3 top-2.5 w-4 h-4 text-slate-400" />
          <Input
            value={query}
            onChange={(e) => onQuery(e.target.value)}
            placeholder="ابحث في السجلات: كلمات البحث، المواقع، أرقام الهواتف، رسائل واتساب..."
            className="pr-9 h-9 text-sm bg-slate-50"
          />
        </div>
      </div>

      {/* أثناء التمرير بالماوس فوق القائمة نتوقف عن التحديث المباشر
          حتى لا تختفي التفاصيل والروابط التي يشير إليها المؤشر */}
      <div
        onMouseEnter={() => onHoverChange?.(true)}
        onMouseLeave={() => onHoverChange?.(false)}
      >
        <ScrollArea className="max-h-[480px]">
          {events.length === 0 ? (
            <div className="p-10 text-center text-sm text-slate-500">
              لا توجد سجلات مطابقة — عند نشاط أي جهاز فرعي ستظهر الأحداث هنا فورًا.
            </div>
          ) : (
            <div className="divide-y divide-slate-100">
              {events.map((ev) => {
                const whats = ev.type === "WHATSAPP" ? whatsMeta(ev.meta) : null;
                const isWeb = ev.type === "SEARCH" || ev.type === "BROWSER";
                return (
                  <div key={ev.id} className="px-4 py-3 flex items-start gap-3 hover:bg-slate-50/70 transition-colors">
                    <div className={cn("w-9 h-9 rounded-lg flex items-center justify-center shrink-0 mt-0.5", ICON_STYLES[ev.type])}>
                      <EventIcon ev={ev} />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-sm font-medium text-slate-800 break-all" title={ev.title ?? undefined}>
                          {ev.type === "CALL"
                            ? ev.phone ?? "رقم غير معروف"
                            : ev.title ?? prettyUrl(ev.url ?? "") ?? "—"}
                        </span>
                        <Badge variant="secondary" className="text-[10px] bg-slate-100 text-slate-600 shrink-0">
                          {TYPE_LABELS[ev.type] ?? ev.type}
                        </Badge>
                        {whats?.group && (
                          <Badge variant="secondary" className="text-[10px] bg-emerald-50 text-emerald-700 shrink-0">
                            مجموعة
                          </Badge>
                        )}
                        {ev.incognito && (
                          <Badge className="text-[10px] bg-slate-800 text-white gap-1 shrink-0">
                            <EyeOff className="w-3 h-3" /> وضع خفي
                          </Badge>
                        )}
                        {ev.type === "CALL" && ev.durationSec != null && ev.durationSec > 0 && (
                          <span className="text-[11px] text-slate-500">{formatDuration(ev.durationSec)}</span>
                        )}
                      </div>

                      {isWeb && ev.url ? (
                        /* بحث/تصفح: الرابط كاملًا عند التمرير، ويُفتح بنقرة — ولا يختفي أثناء التمرير */
                        <a
                          href={ev.url}
                          target="_blank"
                          rel="noreferrer"
                          title={ev.url}
                          dir="auto"
                          onClick={(e) => e.stopPropagation()}
                          className="text-xs text-sky-600 hover:text-sky-700 hover:underline truncate mt-1 block cursor-pointer"
                        >
                          {prettyUrl(ev.url)}
                        </a>
                      ) : (
                        <p className="text-xs text-slate-500 truncate mt-1" dir="auto" title={detailText(ev) || undefined}>
                          {isWeb
                            ? ev.title ?? ""
                            : detailText(ev)}
                        </p>
                      )}

                      <p className="text-[11px] text-slate-400 mt-0.5">
                        {ev.device.name} · {formatDateTime(ev.occurredAt)} · {formatClock(ev.occurredAt)}
                        {ev.type === "WHATSAPP" && ev.app ? ` · ${ev.app}` : ""}
                      </p>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </ScrollArea>
      </div>
      {events.length > 0 && (
        <div className="px-4 py-2 border-t border-slate-100 text-[11px] text-slate-400">
          آخر {events.length} حدث — الأحداث تُسجل لحظة حدوثها على الجهاز الفرعي.
        </div>
      )}
    </div>
  );
}

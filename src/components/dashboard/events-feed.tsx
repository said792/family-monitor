"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  Search, Globe, Phone, Grid3X3, EyeOff, ArrowDownLeft, ArrowUpRight,
  PhoneMissed, Package, Loader2, RefreshCw, MessageCircle, ExternalLink,
  Trash2, ChevronDown, ChevronUp, CalendarDays,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Switch } from "@/components/ui/switch";
import { Label } from "@/components/ui/label";
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import {
  api, formatClock, formatDateTime, formatDuration, prettyUrl,
  type EventRow, type GroupRow, type EventType,
} from "@/lib/client";
import { cn } from "@/lib/utils";
import { toast } from "@/hooks/use-toast";

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
  { value: "WHATSAPP", label: "واتساب" },
  { value: "APP", label: "تطبيقات" },
];

const DAY_OPTIONS = [
  { value: "today", label: "اليوم" },
  { value: "yesterday", label: "أمس" },
  { value: "all", label: "كل الأيام" },
];

const GROUP_PAGE = 60;
const EVENTS_PAGE = 300;

const ICON_STYLES: Record<string, string> = {
  SEARCH: "bg-amber-100 text-amber-700",
  BROWSER: "bg-sky-100 text-sky-700",
  CALL: "bg-rose-100 text-rose-700",
  APP: "bg-violet-100 text-violet-700",
  WHATSAPP: "bg-emerald-100 text-emerald-700",
};

function EventIcon({ type }: { type: string }) {
  if (type === "CALL") {
    return <Phone className="w-4 h-4" />;
  }
  if (type === "APP") return <Grid3X3 className="w-4 h-4" />;
  if (type === "SEARCH") return <Search className="w-4 h-4" />;
  if (type === "WHATSAPP") return <MessageCircle className="w-4 h-4" />;
  return <Globe className="w-4 h-4" />;
}

function CallIcon({ direction }: { direction: string | null }) {
  if (direction === "MISSED") return <PhoneMissed className="w-4 h-4" />;
  if (direction === "INCOMING") return <ArrowDownLeft className="w-4 h-4" />;
  return <ArrowUpRight className="w-4 h-4" />;
}

function whatsappText(meta: string | null): string {
  if (!meta) return "";
  try {
    const obj = JSON.parse(meta);
    return typeof obj?.text === "string" ? obj.text : "";
  } catch {
    return "";
  }
}

/** نطاق اليوم المحدد (من/إلى) بصيغة ISO — بتوقيت الجهاز الأساسي */
function dayRange(day: string): { from?: string; to?: string } {
  if (day === "all") return {};
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  if (day === "yesterday") start.setDate(start.getDate() - 1);
  const end = new Date(start);
  end.setDate(end.getDate() + 1);
  if (day !== "today" && day !== "yesterday" && /^\d{4}-\d{2}-\d{2}$/.test(day)) {
    const [y, m, d] = day.split("-").map(Number);
    start.setFullYear(y, m - 1, d);
    end.setFullYear(y, m - 1, d + 1);
  }
  return { from: start.toISOString(), to: end.toISOString() };
}

function groupTitle(g: GroupRow): string {
  const s = g.sample;
  if (!s) return g.groupKey;
  if (s.type === "CALL") return s.phone ?? g.groupKey;
  if (s.type === "APP") return s.app ?? g.groupKey;
  if (s.type === "WHATSAPP") return s.title ?? g.groupKey;
  if (s.type === "SEARCH") return s.title ?? g.groupKey;
  return s.title || prettyUrl(s.url ?? "") || g.groupKey;
}

function groupSubtitle(g: GroupRow): string {
  const s = g.sample;
  if (!s) return "";
  if (s.type === "CALL") {
    return s.direction === "INCOMING" ? "مكالمة واردة"
      : s.direction === "MISSED" ? "مكالمة فائتة" : "مكالمة صادرة";
  }
  if (s.type === "APP") return s.packageName ?? "";
  if (s.type === "WHATSAPP") return whatsappText(s.meta).slice(0, 90);
  if (s.type === "SEARCH") {
    return [s.app, s.url ? prettyUrl(s.url) : ""].filter(Boolean).join(" — ");
  }
  return s.url ? prettyUrl(s.url) : "";
}

function groupCountLabel(type: string, n: number): string {
  switch (type) {
    case "CALL": return n === 1 ? "مكالمة واحدة" : `${n} مكالمات`;
    case "WHATSAPP": return n === 1 ? "رسالة واحدة" : `${n} رسائل`;
    case "APP": return n === 1 ? "مرة واحدة" : `${n} مرات فتح`;
    case "SEARCH": return n === 1 ? "عملية بحث واحدة" : `${n} عمليات بحث`;
    default: return n === 1 ? "زيارة واحدة" : `${n} زيارات`;
  }
}

function rowTitle(ev: EventRow): string {
  if (ev.type === "CALL") return ev.phone ?? "رقم غير معروف";
  if (ev.type === "APP") return ev.app ?? ev.packageName ?? "—";
  if (ev.type === "WHATSAPP") return ev.title ?? "واتساب";
  return ev.title ?? (ev.url ? prettyUrl(ev.url) : "—");
}

function rowSubtitle(ev: EventRow): string {
  if (ev.type === "CALL") {
    return `${ev.direction === "INCOMING" ? "مكالمة واردة" : ev.direction === "MISSED" ? "مكالمة فائتة" : "مكالمة صادرة"}${ev.app ? ` — ${ev.app}` : ""}`;
  }
  if (ev.type === "APP") return ev.packageName ?? "";
  if (ev.type === "WHATSAPP") {
    const text = whatsappText(ev.meta ?? null);
    const isGroup = whatsappIsGroup(ev.meta ?? null);
    const app = ev.app ?? "";
    const parts = [
      ev.direction === "OUTGOING" ? `مرسلة${app ? ` — ${app}` : ""}` : `${app}`.trim(),
      text,
    ].filter(Boolean);
    if (isGroup && ev.direction !== "OUTGOING") parts.push("من مجموعة");
    return parts.join(" — ");
  }
  return ev.url ? prettyUrl(ev.url) : (ev.packageName ?? ev.app ?? "");
}

/** هل رسالة واتساب من مجموعة؟ (علامة داخل meta) */
function whatsappIsGroup(meta: string | null): boolean {
  if (!meta) return false;
  try {
    return JSON.parse(meta)?.group === true;
  } catch {
    return false;
  }
}

// ---------- سطر سجل مفرد (قابل للضغط لفتح الصفحة + حذف) ----------

function EventLine({
  ev,
  onDelete,
  deleting,
  showDevice,
}: {
  ev: EventRow;
  onDelete: (id: string) => void;
  deleting: boolean;
  showDevice?: boolean;
}) {
  const hasLink = (ev.type === "SEARCH" || ev.type === "BROWSER") && !!ev.url;
  const isCall = ev.type === "CALL";

  const content = (
    <div className="flex-1 min-w-0">
      <div className="flex items-center gap-2 flex-wrap">
        {isCall ? <CallIcon direction={ev.direction} /> : null}
        <span className="text-sm font-medium text-slate-800 break-all">{rowTitle(ev)}</span>
        {ev.type === "WHATSAPP" && ev.direction && (
          <Badge
            className={cn(
              "text-[10px] gap-1 shrink-0",
              ev.direction === "OUTGOING"
                ? "bg-sky-600 text-white"
                : "bg-emerald-600 text-white"
            )}
          >
            {ev.direction === "OUTGOING" ? "صادرة" : "واردة"}
          </Badge>
        )}
        {ev.incognito && (
          <Badge className="text-[10px] bg-slate-800 text-white gap-1 shrink-0">
            <EyeOff className="w-3 h-3" /> خفي
          </Badge>
        )}
        {ev.type === "CALL" && ev.durationSec != null && ev.durationSec > 0 && (
          <span className="text-[11px] text-slate-500">{formatDuration(ev.durationSec)}</span>
        )}
      </div>
      {rowSubtitle(ev) && (
        <p className="text-xs text-slate-500 truncate mt-0.5" dir="auto">{rowSubtitle(ev)}</p>
      )}
      <p className="text-[11px] text-slate-400 mt-0.5">
        {showDevice && ev.device ? `${ev.device.name} · ` : ""}
        {formatDateTime(ev.occurredAt)} · {formatClock(ev.occurredAt)}
        {hasLink && <ExternalLink className="w-3 h-3 inline mr-1 text-slate-400" />}
      </p>
    </div>
  );

  return (
    <div className="flex items-start gap-2 px-3 py-2.5 hover:bg-slate-50/70 transition-colors">
      <div className={cn("w-8 h-8 rounded-lg flex items-center justify-center shrink-0 mt-0.5", ICON_STYLES[ev.type])}>
        <EventIcon type={ev.type} />
      </div>
      {hasLink ? (
        <a
          href={ev.url as string}
          target="_blank"
          rel="noreferrer"
          className="flex-1 min-w-0 cursor-pointer"
          title="فتح الصفحة الأصلية"
        >
          {content}
        </a>
      ) : (
        content
      )}
      <Button
        variant="ghost"
        size="icon"
        className="h-7 w-7 shrink-0 text-slate-300 hover:text-red-600"
        onClick={() => onDelete(ev.id)}
        disabled={deleting}
        title="حذف هذا السجل"
      >
        <Trash2 className="w-3.5 h-3.5" />
      </Button>
    </div>
  );
}

// ---------- المكون الرئيسي ----------

export function EventsFeed({
  deviceId,
  typeFilter,
  onTypeFilter,
  query,
  onQuery,
  live,
  onLive,
  onChanged,
}: {
  deviceId: string | null;
  typeFilter: string;
  onTypeFilter: (t: string) => void;
  query: string;
  onQuery: (q: string) => void;
  live: boolean;
  onLive: (v: boolean) => void;
  onChanged: () => void;
}) {
  const [dayFilter, setDayFilter] = useState("today");
  const [customDate, setCustomDate] = useState("");
  const [loading, setLoading] = useState(false);
  const [deleting, setDeleting] = useState(false);

  // العرض المجمّع
  const [groups, setGroups] = useState<GroupRow[]>([]);
  const [groupsTotal, setGroupsTotal] = useState(0);
  const [hasMoreGroups, setHasMoreGroups] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);

  // العرض المسطح (تبويب الكل)
  const [events, setEvents] = useState<EventRow[]>([]);
  const [eventsTotal, setEventsTotal] = useState(0);
  const [hasMoreEvents, setHasMoreEvents] = useState(false);

  // توسيع مجموعة
  const [expanded, setExpanded] = useState<string | null>(null);
  const [expandedType, setExpandedType] = useState<string>("");
  const [expandedEvents, setExpandedEvents] = useState<EventRow[]>([]);
  const [expandedTotal, setExpandedTotal] = useState(0);
  const [expandedLoading, setExpandedLoading] = useState(false);
  const [expandedOffset, setExpandedOffset] = useState(0);
  const [expandedHasMore, setExpandedHasMore] = useState(false);

  // حذف مجموعة
  const [pendingDelete, setPendingDelete] = useState<{ groupKey: string; type: string; title: string; count: number } | null>(null);

  const queryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [debouncedQuery, setDebouncedQuery] = useState("");

  useEffect(() => {
    if (queryTimer.current) clearTimeout(queryTimer.current);
    queryTimer.current = setTimeout(() => setDebouncedQuery(query), 400);
    return () => {
      if (queryTimer.current) clearTimeout(queryTimer.current);
    };
  }, [query]);

  const effectiveDay = /^\d{4}-\d{2}-\d{2}$/.test(customDate) ? customDate : dayFilter;
  const range = dayRange(effectiveDay);
  const isGrouped = typeFilter !== "ALL";

  const baseParams = useCallback(
    (extra?: Record<string, string>) => {
      const p = new URLSearchParams();
      if (deviceId) p.set("deviceId", deviceId);
      if (debouncedQuery) p.set("q", debouncedQuery);
      if (range.from) p.set("from", range.from);
      if (range.to) p.set("to", range.to);
      if (extra) for (const [k, v] of Object.entries(extra)) p.set(k, v);
      return p.toString();
    },
    [deviceId, debouncedQuery, range.from, range.to]
  );

  /** جلب أول صفحة (مجمّع أو مسطح) */
  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      if (typeFilter === "ALL") {
        const r = await api<{ events: EventRow[]; total: number; hasMore: boolean }>(
          `/api/admin/events?${baseParams()}`
        );
        setEvents(r.events);
        setEventsTotal(r.total);
        setHasMoreEvents(r.hasMore);
      } else {
        const fetchSummary = async (type: string) =>
          api<{ groups: GroupRow[]; total: number; hasMore: boolean }>(
            `/api/admin/summary?${baseParams({ type })}`
          );
        if (typeFilter === "SEARCH_BROWSER") {
          const [s, b] = await Promise.all([fetchSummary("SEARCH"), fetchSummary("BROWSER")]);
          const merged = [...s.groups, ...b.groups].sort(
            (x, y) => new Date(y.lastAt).getTime() - new Date(x.lastAt).getTime()
          );
          setGroups(merged);
          setGroupsTotal(s.total + b.total);
          setHasMoreGroups(s.hasMore || b.hasMore);
        } else {
          const r = await fetchSummary(typeFilter);
          setGroups(r.groups);
          setGroupsTotal(r.total);
          setHasMoreGroups(r.hasMore);
        }
      }
      setExpanded(null);
      setExpandedEvents([]);
    } catch {
      /* تجاهل أخطاء التحديث المؤقتة */
    } finally {
      setLoading(false);
    }
  }, [typeFilter, baseParams]);

  // تحميل المزيد من المجموعات
  async function loadMoreGroups() {
    if (loadingMore || typeFilter === "ALL") return;
    setLoadingMore(true);
    try {
      const fetchSummary = async (type: string, offset: number) =>
        api<{ groups: GroupRow[]; total: number; hasMore: boolean }>(
          `/api/admin/summary?${baseParams({ type, offset: String(offset) })}`
        );
      if (typeFilter === "SEARCH_BROWSER") {
        const so = groups.filter((g) => g.sample?.type === "SEARCH").length;
        const bo = groups.filter((g) => g.sample?.type === "BROWSER").length;
        const [s, b] = await Promise.all([
          fetchSummary("SEARCH", so),
          fetchSummary("BROWSER", bo),
        ]);
        setGroups((prev) => [...prev, ...[...s.groups, ...b.groups].sort(
          (x, y) => new Date(y.lastAt).getTime() - new Date(x.lastAt).getTime()
        )]);
        setHasMoreGroups(s.hasMore || b.hasMore);
      } else {
        const r = await fetchSummary(typeFilter, groups.length);
        setGroups((prev) => [...prev, ...r.groups]);
        setHasMoreGroups(r.hasMore);
      }
    } catch {
      /* تجاهل */
    } finally {
      setLoadingMore(false);
    }
  }

  // تحميل المزيد من السجلات المسطحة
  async function loadMoreEvents() {
    if (loading || typeFilter !== "ALL") return;
    setLoading(true);
    try {
      const r = await api<{ events: EventRow[]; total: number; hasMore: boolean }>(
        `/api/admin/events?${baseParams({ offset: String(events.length) })}`
      );
      setEvents((prev) => [...prev, ...r.events]);
      setEventsTotal(r.total);
      setHasMoreEvents(r.hasMore);
    } catch {
      /* تجاهل */
    } finally {
      setLoading(false);
    }
  }

  // عند تغيير الفلاتر — إعادة التحميل
  useEffect(() => {
    refresh();
  }, [refresh, effectiveDay]);

  // التحديث المباشر
  useEffect(() => {
    if (!live) return;
    const id = setInterval(() => refresh(), 5000);
    return () => clearInterval(id);
  }, [live, refresh]);

  // توسيع مجموعة: كل سجلاتها داخل النطاق المحدد
  async function toggleExpand(g: GroupRow) {
    if (expanded === g.groupKey && expandedType === (g.sample?.type ?? "")) {
      setExpanded(null);
      setExpandedEvents([]);
      return;
    }
    setExpanded(g.groupKey);
    setExpandedType(g.sample?.type ?? "");
    setExpandedLoading(true);
    setExpandedEvents([]);
    setExpandedOffset(0);
    try {
      const type = g.sample?.type ?? "";
      const r = await api<{ events: EventRow[]; total: number; hasMore: boolean }>(
        `/api/admin/events?${baseParams({ type, group: g.groupKey })}`
      );
      setExpandedEvents(r.events);
      setExpandedTotal(r.total);
      setExpandedHasMore(r.hasMore);
      setExpandedOffset(r.events.length);
    } catch {
      toast({ title: "خطأ", description: "تعذر تحميل تفاصيل المجموعة", variant: "destructive" });
    } finally {
      setExpandedLoading(false);
    }
  }

  async function loadMoreExpanded() {
    if (expandedLoading || !expanded) return;
    setExpandedLoading(true);
    try {
      const r = await api<{ events: EventRow[]; total: number; hasMore: boolean }>(
        `/api/admin/events?${baseParams({ type: expandedType, group: expanded, offset: String(expandedOffset) })}`
      );
      setExpandedEvents((prev) => [...prev, ...r.events]);
      setExpandedTotal(r.total);
      setExpandedHasMore(r.hasMore);
      setExpandedOffset((prev) => prev + r.events.length);
    } catch {
      /* تجاهل */
    } finally {
      setExpandedLoading(false);
    }
  }

  // حذف سجل مفرد
  async function deleteOne(id: string) {
    setDeleting(true);
    try {
      const r = await api<{ deleted: number }>("/api/admin/events-delete", {
        method: "POST",
        body: JSON.stringify({ ids: [id] }),
      });
      toast({ title: `تم حذف ${r.deleted} سجل` });
      setEvents((prev) => prev.filter((e) => e.id !== id));
      setExpandedEvents((prev) => prev.filter((e) => e.id !== id));
      onChanged();
      refresh();
    } catch (e) {
      toast({ title: "خطأ", description: (e as Error).message, variant: "destructive" });
    } finally {
      setDeleting(false);
    }
  }

  // حذف مجموعة كاملة
  async function deleteGroup() {
    if (!pendingDelete) return;
    setDeleting(true);
    try {
      const body: Record<string, unknown> = {
        type: pendingDelete.type,
        groupKey: pendingDelete.groupKey,
      };
      if (deviceId) body.deviceId = deviceId;
      // عند تفعيل فلتر يوم نحذف داخل اليوم المحدد فقط
      if (range.from) body.from = range.from;
      if (range.to) body.to = range.to;
      const r = await api<{ deleted: number }>("/api/admin/events-delete", {
        method: "POST",
        body: JSON.stringify(body),
      });
      toast({ title: `تم حذف ${r.deleted} سجل من «${pendingDelete.title}»` });
      setPendingDelete(null);
      onChanged();
      refresh();
    } catch (e) {
      toast({ title: "خطأ", description: (e as Error).message, variant: "destructive" });
    } finally {
      setDeleting(false);
    }
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-white overflow-hidden flex flex-col">
      {/* الشريط العلوي: التبويبات + الأيام + التحديث */}
      <div className="px-4 pt-3 border-b border-slate-100 space-y-3">
        <div className="flex items-center justify-between gap-2 flex-wrap">
          <Tabs value={typeFilter} onValueChange={onTypeFilter}>
            <TabsList className="h-8 bg-slate-100">
              {TABS.map((t) => (
                <TabsTrigger key={t.value} value={t.value} className="text-xs h-7 px-3">
                  {t.label}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-2">
              <Switch id="live" checked={live} onCheckedChange={onLive} />
              <Label htmlFor="live" className="text-xs text-slate-600 flex items-center gap-1 cursor-pointer">
                <span className={cn("w-2 h-2 rounded-full", live ? "bg-emerald-500 animate-pulse" : "bg-slate-300")} />
                تحديث مباشر
              </Label>
            </div>
            <Button variant="outline" size="sm" onClick={refresh} className="h-8 gap-1 text-xs">
              {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
              تحديث
            </Button>
          </div>
        </div>

        {/* تنقّل الأيام — المراقبة يومية */}
        <div className="flex items-center gap-2 flex-wrap pb-1">
          <CalendarDays className="w-4 h-4 text-slate-400" />
          {DAY_OPTIONS.map((d) => (
            <button
              key={d.value}
              onClick={() => {
                setDayFilter(d.value);
                setCustomDate("");
              }}
              className={cn(
                "text-xs rounded-full px-3 py-1 border transition-colors",
                effectiveDay === d.value
                  ? "bg-emerald-600 text-white border-emerald-600"
                  : "bg-white text-slate-600 border-slate-200 hover:border-emerald-400"
              )}
            >
              {d.label}
            </button>
          ))}
          <input
            type="date"
            value={customDate}
            onChange={(e) => setCustomDate(e.target.value)}
            className="text-xs rounded-full px-3 py-1 border border-slate-200 bg-white text-slate-600"
            title="اختيار يوم محدد"
          />
        </div>

        <div className="pb-3 relative">
          <Search className="absolute right-3 top-2.5 w-4 h-4 text-slate-400" />
          <Input
            value={query}
            onChange={(e) => onQuery(e.target.value)}
            placeholder="ابحث في السجلات: كلمات البحث، المواقع، أرقام الهواتف، أسماء التطبيقات..."
            className="pr-9 h-9 text-sm bg-slate-50"
          />
        </div>
      </div>

      {/* المحتوى */}
      <div className="max-h-[560px] overflow-y-auto">
        {loading && groups.length === 0 && events.length === 0 ? (
          <div className="p-10 text-center"><Loader2 className="w-6 h-6 animate-spin text-emerald-500 mx-auto" /></div>
        ) : isGrouped ? (
          groups.length === 0 ? (
            <div className="p-10 text-center text-sm text-slate-500">
              لا توجد سجلات مطابقة — عند نشاط أي جهاز فرعي ستظهر المجموعات هنا.
            </div>
          ) : (
            <div className="divide-y divide-slate-100">
              {groups.map((g) => {
                const type = g.sample?.type ?? "";
                const isOpen = expanded === g.groupKey;
                return (
                  <div key={`${type}-${g.groupKey}`} className="border-b border-slate-50 last:border-0">
                    <div className="px-4 py-3 flex items-start gap-3 hover:bg-slate-50/70 transition-colors">
                      <button
                        className={cn("w-9 h-9 rounded-lg flex items-center justify-center shrink-0 mt-0.5", ICON_STYLES[type] ?? "bg-slate-100")}
                        onClick={() => toggleExpand(g)}
                        title="عرض كل السجلات"
                      >
                        <EventIcon type={type} />
                      </button>
                      <button className="flex-1 min-w-0 text-right" onClick={() => toggleExpand(g)}>
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="text-sm font-medium text-slate-800 break-all" dir="auto">
                            {groupTitle(g)}
                          </span>
                          <Badge variant="secondary" className="text-[10px] bg-emerald-50 text-emerald-700 border border-emerald-200 shrink-0">
                            {groupCountLabel(type, g.count)}
                          </Badge>
                          {g.sample?.incognito && (
                            <Badge className="text-[10px] bg-slate-800 text-white gap-1 shrink-0">
                              <EyeOff className="w-3 h-3" /> خفي
                            </Badge>
                          )}
                        </div>
                        {groupSubtitle(g) && (
                          <p className="text-xs text-slate-500 truncate mt-0.5" dir="auto">{groupSubtitle(g)}</p>
                        )}
                        <p className="text-[11px] text-slate-400 mt-0.5">
                          آخر ظهور: {formatDateTime(g.lastAt)} · {formatClock(g.lastAt)}
                        </p>
                      </button>
                      <div className="flex items-center gap-1 shrink-0">
                        {(type === "SEARCH" || type === "BROWSER") && g.sample?.url && (
                          <a
                            href={g.sample.url}
                            target="_blank"
                            rel="noreferrer"
                            className="h-7 w-7 flex items-center justify-center rounded-md text-slate-400 hover:text-emerald-600 hover:bg-emerald-50"
                            title="فتح الصفحة"
                          >
                            <ExternalLink className="w-3.5 h-3.5" />
                          </a>
                        )}
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7 text-slate-300 hover:text-red-600"
                          onClick={() =>
                            setPendingDelete({
                              groupKey: g.groupKey,
                              type,
                              title: groupTitle(g),
                              count: g.count,
                            })
                          }
                          title="حذف كل سجلات هذه المجموعة"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7 text-slate-400"
                          onClick={() => toggleExpand(g)}
                        >
                          {isOpen ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                        </Button>
                      </div>
                    </div>

                    {/* تفاصيل المجموعة الموسعة */}
                    {isOpen && (
                      <div className="bg-slate-50/60 border-t border-slate-100">
                        {expandedLoading && expandedEvents.length === 0 ? (
                          <div className="p-4 text-center"><Loader2 className="w-4 h-4 animate-spin text-slate-400 mx-auto" /></div>
                        ) : (
                          <div className="divide-y divide-slate-100/70">
                            {expandedEvents.map((ev) => (
                              <EventLine key={ev.id} ev={ev} onDelete={deleteOne} deleting={deleting} />
                            ))}
                          </div>
                        )}
                        <div className="px-3 py-2 flex items-center justify-between">
                          <span className="text-[11px] text-slate-400">
                            {expandedTotal} سجل في المجموعة
                          </span>
                          {expandedHasMore && (
                            <Button variant="outline" size="sm" className="h-7 text-xs" onClick={loadMoreExpanded} disabled={expandedLoading}>
                              {expandedLoading ? <Loader2 className="w-3 h-3 animate-spin" /> : null}
                              تحميل الباقي ({expandedTotal - expandedEvents.length})
                            </Button>
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )
        ) : events.length === 0 ? (
          <div className="p-10 text-center text-sm text-slate-500">
            لا توجد سجلات مطابقة — عند نشاط أي جهاز فرعي ستظهر الأحداث هنا فورًا.
          </div>
        ) : (
          <div className="divide-y divide-slate-100">
            {events.map((ev) => (
              <EventLine key={ev.id} ev={ev} onDelete={deleteOne} deleting={deleting} showDevice />
            ))}
          </div>
        )}

        {/* تحميل المزيد */}
        {isGrouped && hasMoreGroups && groups.length > 0 && (
          <div className="p-3 text-center border-t border-slate-100">
            <Button variant="outline" size="sm" className="text-xs gap-1" onClick={loadMoreGroups} disabled={loadingMore}>
              {loadingMore ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : null}
              تحميل مجموعات أكثر — معروض {groups.length} من {groupsTotal}
            </Button>
          </div>
        )}
        {!isGrouped && hasMoreEvents && events.length > 0 && (
          <div className="p-3 text-center border-t border-slate-100">
            <Button variant="outline" size="sm" className="text-xs gap-1" onClick={loadMoreEvents} disabled={loading}>
              {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : null}
              تحميل المزيد — معروض {events.length} من {eventsTotal}
            </Button>
          </div>
        )}
      </div>

      {(isGrouped ? groups.length > 0 : events.length > 0) && (
        <div className="px-4 py-2 border-t border-slate-100 text-[11px] text-slate-400">
          {isGrouped
            ? `مجموعات السجلات معروضة بالكامل من قاعدة البيانات — اضغط أي مجموعة لعرض كل سجلاتها، والضغط على أي بحث يفتح الصفحة الأصلية.`
            : `معروض ${events.length} من أصل ${eventsTotal} سجل — كل السجلات متاحة، اضغط «تحميل المزيد» لعرض الباقي.`}
        </div>
      )}

      {/* تأكيد حذف مجموعة */}
      <AlertDialog open={pendingDelete !== null} onOpenChange={(v) => !v && setPendingDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>حذف سجلات «{pendingDelete?.title}»؟</AlertDialogTitle>
            <AlertDialogDescription>
              {effectiveDay === "all"
                ? `سيتم حذف كل سجلات هذه المجموعة (${pendingDelete?.count} سجل) في كل الأيام نهائيًا.`
                : `سيتم حذف سجلات هذه المجموعة الموجودة في اليوم المحدد فقط. لا يمكن التراجع.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>إلغاء</AlertDialogCancel>
            <AlertDialogAction onClick={deleteGroup} className="bg-red-600 hover:bg-red-500">
              حذف نهائي
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

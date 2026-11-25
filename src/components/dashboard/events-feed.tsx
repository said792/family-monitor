"use client";

import { useMemo, useState } from "react";
import {
  Search, Globe, Phone, Grid3X3, EyeOff, ArrowDownLeft, ArrowUpRight, PhoneMissed,
  MessageCircle, Loader2, RefreshCw, ChevronDown, List, MessagesSquare,
} from "lucide-react";
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
  timeAgo,
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

const GROUP_TYPES: Record<string, string> = {
  WHATSAPP: "واتساب",
  CALL: "مكالمات",
  APP: "تطبيقات",
  SEARCH_BROWSER: "بحث وتصفح",
};

const AVATAR_STYLES: Record<string, string> = {
  WHATSAPP: "bg-emerald-100 text-emerald-700",
  CALL: "bg-rose-100 text-rose-700",
  APP: "bg-violet-100 text-violet-700",
  SEARCH_BROWSER: "bg-sky-100 text-sky-700",
};

const ICON_STYLES: Record<string, string> = {
  SEARCH: "bg-amber-100 text-amber-700",
  BROWSER: "bg-sky-100 text-sky-700",
  CALL: "bg-rose-100 text-rose-700",
  APP: "bg-violet-100 text-violet-700",
  WHATSAPP: "bg-emerald-100 text-emerald-700",
};

// ---------- أدوات مساعدة ----------

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

/** ملكية الموقع من الرابط (لتجميع البحث والتصفح) */
function domainOf(url: string | null): string {
  if (!url) return "";
  try {
    return new URL(url).host.replace(/^www\./, "");
  } catch {
    return "";
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
    if (m.group && m.sender && ev.title && m.sender !== ev.title) return `${m.sender}: ${body}`;
    return body;
  }
  return ev.url ? prettyUrl(ev.url) : ev.packageName ?? ev.app ?? "";
}

/** مفتاح واسم المجموعة التي ينتمي إليها الحدث */
function groupOf(ev: EventRow): { key: string; type: string; label: string } {
  switch (ev.type) {
    case "WHATSAPP":
      return {
        key: `WA|${ev.deviceId}|${ev.title ?? "غير معروف"}`,
        type: "WHATSAPP",
        label: ev.title || "غير معروف",
      };
    case "CALL":
      return {
        key: `CALL|${ev.deviceId}|${ev.phone ?? "unknown"}`,
        type: "CALL",
        label: ev.phone || "رقم غير معروف",
      };
    case "APP":
      return {
        key: `APP|${ev.deviceId}|${ev.packageName || ev.app || "?"}`,
        type: "APP",
        label: ev.app || ev.packageName || "تطبيق",
      };
    case "SEARCH":
    case "BROWSER": {
      const d = domainOf(ev.url);
      // بحث مكتوب كنص (بدون رابط) يجمع كله في محادثة «عمليات البحث»
      if (ev.type === "SEARCH" && !d) {
        return { key: `WEB|${ev.deviceId}|searches`, type: "SEARCH_BROWSER", label: "عمليات البحث" };
      }
      return {
        key: `WEB|${ev.deviceId}|${d || "web"}`,
        type: "SEARCH_BROWSER",
        label: d || ev.title || "موقع",
      };
    }
    default:
      return { key: `${ev.type}|${ev.deviceId}`, type: ev.type, label: ev.title ?? "حدث" };
  }
}

interface EventGroup {
  key: string;
  type: string;
  label: string;
  events: EventRow[];
}

// ---------- أيقونات الاتجاه ----------

function DirectionIcon({ ev }: { ev: EventRow }) {
  if (ev.direction === "MISSED") return <PhoneMissed className="w-3.5 h-3.5 text-rose-500 shrink-0" />;
  if (ev.direction === "OUTGOING") return <ArrowUpRight className="w-3.5 h-3.5 text-sky-500 shrink-0" />;
  if (ev.direction === "INCOMING") return <ArrowDownLeft className="w-3.5 h-3.5 text-emerald-500 shrink-0" />;
  return null;
}

function DirectionBadge({ ev }: { ev: EventRow }) {
  if (!ev.direction) return null;
  const label = ev.direction === "INCOMING" ? "واردة" : ev.direction === "MISSED" ? "فائتة" : "صادرة";
  const cls =
    ev.direction === "INCOMING"
      ? "bg-emerald-50 text-emerald-700"
      : ev.direction === "MISSED"
        ? "bg-rose-50 text-rose-700"
        : "bg-sky-50 text-sky-700";
  return (
    <Badge variant="secondary" className={cn("text-[10px] shrink-0 gap-0.5", cls)}>
      <DirectionIcon ev={ev} />
      {label}
    </Badge>
  );
}

function EventIcon({ ev }: { ev: EventRow }) {
  if (ev.type === "CALL") return <Phone className="w-4 h-4" />;
  if (ev.type === "APP") return <Grid3X3 className="w-4 h-4" />;
  if (ev.type === "WHATSAPP") return <MessageCircle className="w-4 h-4" />;
  if (ev.type === "SEARCH") return <Search className="w-4 h-4" />;
  return <Globe className="w-4 h-4" />;
}

// ---------- عنصر داخل المحادثة/السلسلة ----------

function ThreadItem({ ev }: { ev: EventRow }) {
  const whats = ev.type === "WHATSAPP" ? whatsMeta(ev.meta) : null;
  const isWeb = ev.type === "SEARCH" || ev.type === "BROWSER";
  const out = ev.direction === "OUTGOING";

  return (
    <div
      className={cn(
        "rounded-lg px-3 py-2 text-right",
        ev.type === "WHATSAPP"
          ? out
            ? "bg-sky-50"
            : "bg-white"
          : "bg-white"
      )}
    >
      <div className="flex items-center gap-2 flex-wrap">
        <DirectionBadge ev={ev} />
        {whats?.group && (
          <Badge variant="secondary" className="text-[10px] bg-emerald-50 text-emerald-700 shrink-0">
            مجموعة
          </Badge>
        )}
        {whats?.group && whats.sender && (
          <span className="text-[11px] font-medium text-slate-600 shrink-0">{whats.sender}</span>
        )}
        {ev.incognito && (
          <Badge className="text-[10px] bg-slate-800 text-white gap-1 shrink-0">
            <EyeOff className="w-3 h-3" /> وضع خفي
          </Badge>
        )}
        {ev.type === "CALL" && ev.durationSec != null && ev.durationSec > 0 && (
          <span className="text-[11px] text-slate-500">{formatDuration(ev.durationSec)}</span>
        )}
        <span className="text-[10px] text-slate-400 mr-auto shrink-0">
          {formatDateTime(ev.occurredAt)} · {formatClock(ev.occurredAt)}
        </span>
      </div>

      {isWeb && ev.url ? (
        <a
          href={ev.url}
          target="_blank"
          rel="noreferrer"
          title={ev.url}
          dir="auto"
          onClick={(e) => e.stopPropagation()}
          className="text-xs text-sky-600 hover:text-sky-700 hover:underline mt-1 inline-block cursor-pointer"
        >
          {ev.type === "SEARCH" && ev.title ? `${ev.title} — ` : ""}
          {prettyUrl(ev.url)}
        </a>
      ) : (
        <p className="text-xs text-slate-700 mt-1 whitespace-pre-wrap break-words" dir="auto">
          {ev.type === "WHATSAPP"
            ? (whats?.text ?? "—")
            : ev.type === "APP"
              ? `${ev.app ?? ev.packageName ?? ""} — تم فتح التطبيق`
              : detailText(ev)}
        </p>
      )}
    </div>
  );
}

// ---------- لوحة المحادثة الموسعة ----------

function ThreadPanel({ group }: { group: EventGroup }) {
  const [shown, setShown] = useState(30);
  const items = group.events.slice(0, shown);

  return (
    <div className="mx-3 mb-2 rounded-xl border border-slate-200 bg-slate-50/70 px-3 py-3 space-y-1.5">
      <div className="text-[11px] text-slate-500 flex items-center justify-between px-1">
        <span>
          {GROUP_TYPES[group.type] ?? group.type} · {group.events.length} حدث — مرتبة من الأحدث للأقدم
        </span>
      </div>
      {items.map((ev) => (
        <ThreadItem key={ev.id} ev={ev} />
      ))}
      {group.events.length > shown && (
        <Button variant="outline" size="sm" className="w-full h-8 text-xs" onClick={() => setShown(shown + 50)}>
          عرض أقدم ({group.events.length - shown} حدث متبقي)
        </Button>
      )}
    </div>
  );
}

// ---------- صف مجموعة ----------

function GroupRow({
  group,
  expanded,
  onToggle,
}: {
  group: EventGroup;
  expanded: boolean;
  onToggle: () => void;
}) {
  const last = group.events[0];
  const letter = group.label.trim().charAt(0) || "؟";

  return (
    <div>
      <div
        onClick={onToggle}
        className="px-4 py-3 flex items-center gap-3 hover:bg-slate-50/70 cursor-pointer transition-colors"
      >
        <div
          className={cn(
            "w-10 h-10 rounded-full flex items-center justify-center shrink-0 font-bold text-base",
            AVATAR_STYLES[group.type] ?? "bg-slate-100 text-slate-600"
          )}
        >
          {group.type === "SEARCH_BROWSER" ? <Globe className="w-5 h-5" /> : letter}
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-sm font-semibold text-slate-800 break-all" dir="auto">
              {group.label}
            </span>
            <Badge variant="secondary" className="text-[10px] bg-slate-100 text-slate-600 shrink-0">
              {group.events.length} {group.events.length === 1 ? "حدث" : "أحداث"}
            </Badge>
            <Badge variant="secondary" className="text-[10px] bg-white text-slate-500 border shrink-0">
              {GROUP_TYPES[group.type] ?? group.type}
            </Badge>
          </div>
          <p className="text-xs text-slate-500 truncate mt-0.5" dir="auto" title={detailText(last) || undefined}>
            <DirectionIcon ev={last} /> {detailText(last) || "—"}
          </p>
          <p className="text-[11px] text-slate-400 mt-0.5">
            {last.device.name} · {timeAgo(last.occurredAt)}
          </p>
        </div>
        <ChevronDown
          className={cn("w-4 h-4 text-slate-400 shrink-0 transition-transform", expanded && "rotate-180")}
        />
      </div>
      {expanded && <ThreadPanel group={group} />}
    </div>
  );
}

// ---------- الصف القديم (عرض القائمة المسطحة) ----------

function FlatRow({ ev }: { ev: EventRow }) {
  const whats = ev.type === "WHATSAPP" ? whatsMeta(ev.meta) : null;
  const isWeb = ev.type === "SEARCH" || ev.type === "BROWSER";
  return (
    <div className="px-4 py-3 flex items-start gap-3 hover:bg-slate-50/70 transition-colors">
      <div className={cn("w-9 h-9 rounded-lg flex items-center justify-center shrink-0 mt-0.5", ICON_STYLES[ev.type])}>
        <EventIcon ev={ev} />
      </div>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-medium text-slate-800 break-all" title={ev.title ?? undefined}>
            {ev.type === "CALL" ? ev.phone ?? "رقم غير معروف" : ev.title ?? prettyUrl(ev.url ?? "") ?? "—"}
          </span>
          <Badge variant="secondary" className="text-[10px] bg-slate-100 text-slate-600 shrink-0">
            {TYPE_LABELS[ev.type] ?? ev.type}
          </Badge>
          <DirectionBadge ev={ev} />
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
            {isWeb ? ev.title ?? "" : detailText(ev)}
          </p>
        )}

        <p className="text-[11px] text-slate-400 mt-0.5">
          {ev.device.name} · {formatDateTime(ev.occurredAt)} · {formatClock(ev.occurredAt)}
          {ev.type === "WHATSAPP" && ev.app ? ` · ${ev.app}` : ""}
        </p>
      </div>
    </div>
  );
}

// ---------- المكوّن الرئيسي ----------

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
  // وضع العرض: مجمّع (محادثة لكل شخص/رقم/تطبيق/موقع) أو قائمة مسطحة
  const [mode, setMode] = useState<"grouped" | "flat">("grouped");
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const groups = useMemo(() => {
    const map = new Map<string, EventGroup>();
    for (const ev of events) {
      const g = groupOf(ev);
      let grp = map.get(g.key);
      if (!grp) {
        grp = { key: g.key, type: g.type, label: g.label, events: [] };
        map.set(g.key, grp);
      }
      grp.events.push(ev);
    }
    const list = [...map.values()];
    for (const g of list) {
      g.events.sort((a, b) => new Date(b.occurredAt).getTime() - new Date(a.occurredAt).getTime());
    }
    // المجموعات مرتبة حسب آخر نشاط
    list.sort(
      (a, b) => new Date(b.events[0].occurredAt).getTime() - new Date(a.events[0].occurredAt).getTime()
    );
    return list;
  }, [events]);

  function toggleGroup(key: string) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

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
            {/* تبديل طريقة العرض: مجمّع / قائمة */}
            <div className="flex items-center rounded-lg border border-slate-200 overflow-hidden">
              <button
                onClick={() => setMode("grouped")}
                className={cn(
                  "h-8 px-2.5 flex items-center gap-1 text-xs transition-colors",
                  mode === "grouped" ? "bg-emerald-600 text-white" : "bg-white text-slate-600 hover:bg-slate-50"
                )}
                title="عرض مجمّع: محادثة لكل شخص"
              >
                <MessagesSquare className="w-3.5 h-3.5" />
                مجمّع
              </button>
              <button
                onClick={() => setMode("flat")}
                className={cn(
                  "h-8 px-2.5 flex items-center gap-1 text-xs transition-colors",
                  mode === "flat" ? "bg-emerald-600 text-white" : "bg-white text-slate-600 hover:bg-slate-50"
                )}
                title="عرض القائمة المباشرة"
              >
                <List className="w-3.5 h-3.5" />
                قائمة
              </button>
            </div>
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
          ) : mode === "grouped" ? (
            <div className="divide-y divide-slate-100">
              {groups.map((g) => (
                <GroupRow
                  key={g.key}
                  group={g}
                  expanded={expanded.has(g.key)}
                  onToggle={() => toggleGroup(g.key)}
                />
              ))}
            </div>
          ) : (
            <div className="divide-y divide-slate-100">
              {events.map((ev) => (
                <FlatRow key={ev.id} ev={ev} />
              ))}
            </div>
          )}
        </ScrollArea>
      </div>
      {events.length > 0 && (
        <div className="px-4 py-2 border-t border-slate-100 text-[11px] text-slate-400">
          {mode === "grouped"
            ? `${events.length} حدث مجمّعة في ${groups.length} محادثة/سلسلة — اضغط على أي مجموعة لعرض كل محتواها.`
            : `آخر ${events.length} حدث — الأحداث تُسجل لحظة حدوثها على الجهاز الفرعي.`}
        </div>
      )}
    </div>
  );
}

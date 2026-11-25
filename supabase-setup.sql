-- ============================================================
-- نظام المراقبة الأسرية — سكربت إنشاء قاعدة البيانات كاملة
-- للتنفيذ على Supabase: Dashboard → SQL Editor → New query → الصق كل المحتوى → Run
-- متوافق 100% مع Prisma (نفس أسماء الجداول والأعمدة والفهارس التي يتوقعها التطبيق)
-- ============================================================

-- امتداد توليد المعرفات العشوائية
create extension if not exists pgcrypto;

-- ------------------------------------------------------------
-- 1) جدول الأجهزة الفرعية
-- ------------------------------------------------------------
create table if not exists "Device" (
  "id"        text primary key default gen_random_uuid()::text,
  "name"      text not null,
  "platform"  text not null,                     -- windows | android | linux | macos
  "apiKey"    text not null unique,              -- مفتاح الجهاز (رفع فقط)
  "status"    text not null default 'active',    -- active | blocked
  "lastSeen"  timestamp(3),                      -- آخر نبضة اتصال
  "createdAt" timestamp(3) not null default now()
);

-- ------------------------------------------------------------
-- 2) جدول الأحداث (بحث / تصفح / مكالمات / فتح تطبيقات)
-- ------------------------------------------------------------
create table if not exists "Event" (
  "id"          text primary key default gen_random_uuid()::text,
  "deviceId"    text not null references "Device"("id") on delete cascade,
  "type"        text not null,                   -- SEARCH | BROWSER | CALL | APP
  "title"       text,                            -- نص البحث / عنوان الصفحة / اسم التطبيق
  "url"         text,
  "app"         text,                            -- اسم المتصفح أو التطبيق
  "packageName" text,                            -- حزمة الأندرويد
  "direction"   text,                            -- المكالمات: INCOMING | OUTGOING | MISSED
  "phone"       text,
  "durationSec" integer,                         -- مدة المكالمة بالثواني
  "incognito"   boolean not null default false,  -- هل كان التصفح في الوضع الخفي؟
  "occurredAt"  timestamp(3) not null default now(),
  "meta"        text,                            -- بيانات إضافية JSON (نص رسالة واتساب ...)
  "groupKey"    text,                            -- مفتاح التجميع: نص البحث/الدومين/الرقم/المرسل/الحزمة
  "createdAt"   timestamp(3) not null default now()
);

-- الفهارس: السجلات تُقرأ دائمًا حسب الجهاز والوقت، أو النوع والوقت، أو التجميع
-- (نفس الأسماء والترتيب الذي ينشئه Prisma — توافق كامل بدون انزياح)
create index if not exists "Event_deviceId_occurredAt_idx" on "Event" ("deviceId", "occurredAt");
create index if not exists "Event_type_occurredAt_idx"     on "Event" ("type", "occurredAt");
create index if not exists "Event_deviceId_type_groupKey_idx" on "Event" ("deviceId", "type", "groupKey");

-- ------------------------------------------------------------
-- 3) جدول أكواد الاقتران (تسجيل أجهزة جديدة)
-- ------------------------------------------------------------
create table if not exists "PairingCode" (
  "id"        text primary key default gen_random_uuid()::text,
  "code"      text not null unique,              -- كود 6 أرقام
  "used"      boolean not null default false,
  "expiresAt" timestamp(3) not null,             -- صالح 10 دقائق من الإنشاء
  "createdAt" timestamp(3) not null default now()
);

-- ------------------------------------------------------------
-- 4) جدول الإعدادات (كلمة مرور الجهاز الأساسي المشفرة .. إلخ)
-- ------------------------------------------------------------
create table if not exists "Setting" (
  "key"   text primary key,
  "value" text not null
);

-- ============================================================
-- الحماية القصوى: قفل أي وصول للسجلات عبر واجهة Supabase REST API
-- (التطبيق يتصل بقاعدة البيانات مباشرة برابط Connection String،
--  فهذا القفل لا يؤثر عليه — لكنه يمنع أي شخص يمتلك المفتاح العام
--  من قراءة سجلات المراقبة عبر /rest/v1)
-- ============================================================
alter table "Device"      enable row level security;
alter table "Event"       enable row level security;
alter table "PairingCode" enable row level security;
alter table "Setting"     enable row level security;

-- ============================================================
-- الإجراءات المخزنة (Stored Procedures)
-- ============================================================

-- 1) تنظيف أكواد الاقتران المستهلكة والمنتهية
create or replace function cleanup_pairing_codes()
returns integer language plpgsql as $$
declare
  removed integer;
begin
  delete from "PairingCode" where used = true or "expiresAt" < now();
  get diagnostics removed = row_count;
  return removed;
end $$;

-- 2) حذف السجلات الأقدم من عدد أيام محدد (تنظيف مساحة الخطة المجانية)
--    الاستخدام: select cleanup_old_events(180);   ← يحذف ما أقدم من 6 شهور
create or replace function cleanup_old_events(days integer default 180)
returns integer language plpgsql as $$
declare
  removed integer;
begin
  delete from "Event" where "occurredAt" < now() - make_interval(days => greatest(days, 1));
  get diagnostics removed = row_count;
  return removed;
end $$;

-- 3) إحصائيات مجمعة لكل جهاز (عدد البحث/المكالمات/التطبيقات وآخر حدث)
--    الاستخدام: select * from device_stats();
create or replace function device_stats()
returns table (
  "device_id"  text,
  "device_name" text,
  "platform"   text,
  "total"      bigint,
  "searches"   bigint,
  "browsing"   bigint,
  "calls"      bigint,
  "apps"       bigint,
  "last_event" timestamp(3)
) language sql as $$
  select
    d."id",
    d."name",
    d."platform",
    count(e."id"),
    count(*) filter (where e."type" = 'SEARCH'),
    count(*) filter (where e."type" = 'BROWSER'),
    count(*) filter (where e."type" = 'CALL'),
    count(*) filter (where e."type" = 'APP'),
    max(e."occurredAt")
  from "Device" d
  left join "Event" e on e."deviceId" = d."id"
  group by d."id", d."name", d."platform"
  order by count(e."id") desc;
$$;

-- 4) إعادة توليد مفتاح جهاز فورًا لو تسرب أو أردت إيقافه (يعيد المفتاح الجديد)
--    الاستخدام: select rotate_device_api_key('معرف-الجهاز');
create or replace function rotate_device_api_key(p_device_id text)
returns text language plpgsql as $$
declare
  new_key text;
begin
  new_key := encode(gen_random_bytes(24), 'hex');
  update "Device" set "apiKey" = new_key where "id" = p_device_id;
  return new_key;
end $$;

-- 5) حذف جهاز مع كل سجلاته نهائيًا في أمر واحد
--    الاستخدام: select purge_device('معرف-الجهاز');
create or replace function purge_device(p_device_id text)
returns void language plpgsql as $$
begin
  delete from "Device" where "id" = p_device_id;
end $$;

-- ============================================================
-- التنظيف التلقائي اليومي الساعة 3 فجرًا (يحذف السجلات الأقدم من
-- سنة وأكواد الاقتران الميتة) — يعتمد على امتداد pg_cron.
-- محمي داخل كتلة DO: لو لم يتوفر pg_cron لن يفشل السكربت إطلاقًا.
-- لتفعيله لاحقًا: Dashboard → Database → Extensions → pg_cron → Enable
-- ============================================================
create or replace function daily_maintenance()
returns void language plpgsql as $$
begin
  perform cleanup_old_events(365);
  perform cleanup_pairing_codes();
end $$;

do $outer$
begin
  create extension if not exists pg_cron;
  begin
    perform cron.unschedule('daily-cleanup');
  exception when others then null; -- أول تشغيل: لا يوجد عمل مجدول بهذا الاسم
  end;
  perform cron.schedule('daily-cleanup', '0 3 * * *', 'select daily_maintenance()');
exception when others then
  raise notice 'تم تخطي التنظيف التلقائي (pg_cron غير متاح في هذه القاعدة): %', sqlerrm;
end $outer$;

-- ============================================================
-- التحقق من نجاح التنفيذ (شغّل هذه السطرين لترى الجداول الناتجة)
-- ============================================================
-- select table_name from information_schema.tables
--  where table_schema = 'public' order by table_name;
-- select * from device_stats();

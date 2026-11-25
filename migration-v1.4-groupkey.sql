-- ============================================================
-- ترقية قاعدة البيانات إلى v1.4 — عمود «مفتاح التجميع» groupKey
-- الهدف: تجميع كل السجلات على الخادم (بحث/تصفح/مكالمات/تطبيقات/واتساب)
-- بلا أي حد لعدد الصفحات، فتظهر كل عمليات البحث مهما كان عددها (50 أو 5000).
--
-- طريقة التنفيذ: Supabase → Dashboard → SQL Editor → New query
-- → الصق كل المحتوى → Run
-- آمن للتنفيذ عدة مرات (لا يكرر العمود ولا يعيد تعبئة السجلات المعبأة)
-- ============================================================

-- 1) إضافة العمود
alter table "Event" add column if not exists "groupKey" text;

-- 2) فهرس التجميع (نفس الاسم الذي ينشئه Prisma)
create index if not exists "Event_deviceId_type_groupKey_idx"
  on "Event" ("deviceId", "type", "groupKey");

-- 3) تعبئة المفتاح للسجلات القديمة بنفس منطق الخادم الجديد:
--    SEARCH   → نص البحث (أو الدومين إن لم يتوفر)
--    BROWSER  → دومين الموقع بدون www
--    CALL     → رقم الهاتف (أرقام فقط)
--    WHATSAPP → اسم المرسل
--    APP      → اسم حزمة التطبيق
update "Event"
set "groupKey" = case "type"
  when 'SEARCH' then
    coalesce(
      nullif(lower(btrim("title")), ''),
      nullif(lower(regexp_replace(coalesce(substring("url" from '//([^/#?]+)'), ''), '^//(www\.)?', '')), ''),
      lower(btrim("url"))
    )
  when 'BROWSER' then
    coalesce(
      nullif(lower(regexp_replace(coalesce(substring("url" from '//([^/#?]+)'), ''), '^//(www\.)?', '')), ''),
      lower(btrim("url"))
    )
  when 'CALL' then
    coalesce(nullif(regexp_replace("phone", '[^\d+]', '', 'g'), ''), "phone")
  when 'WHATSAPP' then
    nullif(btrim("title"), '')
  when 'APP' then
    coalesce(nullif(btrim("packageName"), ''), nullif(btrim("app"), ''))
  else null
end
where "groupKey" is null;

-- 4) تحقق: عدّ السجلات المعبأة مقابل غير المعبأة
-- select ("groupKey" is null) as بدون_مفتاح, count(*) from "Event" group by 1;

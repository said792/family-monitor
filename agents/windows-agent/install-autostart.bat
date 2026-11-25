@echo off
chcp 65001 >nul
rem تشغيل الوكيل تلقائيًا عند إقلاع ويندوز عبر مجدول المهام (يتطلب صلاحيات المسؤول)
schtasks /Create /TN "FamilyMonitorAgent" /TR "%~dp0run.bat" /SC ONLOGON /F
if errorlevel 1 (
    echo [!] فشل إنشاء المهمة - شغّل هذا الملف بـ "تشغيل كمسؤول"
) else (
    echo [+] سيتم تشغيل وكيل المراقبة تلقائيًا عند كل تسجيل دخول.
)
pause

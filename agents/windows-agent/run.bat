@echo off
chcp 65001 >nul
title وكيل المراقبة - جهاز الكمبيوتر
echo ============================================
echo   وكيل المراقبة الأسرية - جهاز الكمبيوتر
echo ============================================
echo.

where node >nul 2>nul
if errorlevel 1 (
    echo [!] Node.js غير مثبت. نزّله من https://nodejs.org (نسخة LTS).
    pause
    exit /b 1
)

if not exist node_modules (
    echo [+] تثبيت الاعتماديات (مرة واحدة)...
    call npm install --no-fund --no-audit
)

echo [+] تشغيل الوكيل...
node agent.js
pause

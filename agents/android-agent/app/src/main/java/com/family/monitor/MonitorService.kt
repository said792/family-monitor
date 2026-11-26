package com.family.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog

/**
 * خدمة المراقبة الأمامية: تبقى شغالة بوضوح للمستخدم،
 * وترصد فتح التطبيقات + المكالمات وتزامن كل الأحداث مع الخادم.
 */
class MonitorService : Service() {

    private lateinit var handler: Handler
    private var callObserver: ContentObserver? = null
    private var loopTicks = 0
    private var lastDiagAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val loop = object : Runnable {
        override fun run() {
            // 1) رصد فتح التطبيقات
            UsageTracker.poll(this@MonitorService)
            // 1ب) رصد المحتوى المعروض داخل التطبيقات (ريلز/فيديوهات — عنوان كل مقطع ومرات دخوله)
            MediaWatcher.poll(this@MonitorService)
            // 2) مزامنة الأحداث مع الخادم
            try {
                EventStore.sync(this@MonitorService)
            } catch (e: Exception) {
                android.util.Log.e("MonitorService", "تعذرت المزامنة، ستُعاد المحاولة", e)
            }
            // 3) تحديث الإشعار الثابت بذكر جديد كل ~5 دقائق (على الخيط الرئيسي)
            loopTicks++
            // 3أ) تأكيد الإصدار: حدث واحد عند أول تشغيل لكل نسخة جديدة حتى يتأكد
            //     الأب من اللوحة أن الجهاز الفرعي يعمل بالنسخة المحدثة فعلاً
            if (loopTicks == 1) {
                val ver = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
                } catch (_: Exception) { "?" }
                val diagPrefs = getSharedPreferences("diag", MODE_PRIVATE)
                if (diagPrefs.getString("reportedVersion", "") != ver) {
                    diagPrefs.edit().putString("reportedVersion", ver).apply()
                    try {
                        EventStore.addEvent(
                            this@MonitorService,
                            org.json.JSONObject()
                                .put("type", "APP")
                                .put("app", "معلومة إصدار")
                                .put("packageName", "diagnostic")
                                .put("title", "تشغيل الإصدار v$ver ✓ — الجهاز الفرعي يعمل بالنسخة المحدثة")
                        )
                    } catch (_: Exception) {}
                }
            }
            if (loopTicks % 60 == 0) {
                mainHandler.post { startForegroundWithNotification(AdhkarData.shortDhikrForNow()) }
            }
            // 4) تشخيص الخدمات: لو خدمة أساسية معطلة ننبه بمربع في اللوحة
            //    (بعد 5 دقائق من بدء التشغيل، ومرة كل 6 ساعات كحد أقصى)
            if (loopTicks > 60 && System.currentTimeMillis() - lastDiagAt > 6 * 3600_000L) {
                val diag = when {
                    !isAccessibilityEnabled() ->
                        "خدمة إمكانية الوصول غير مفعلة — الرسائل الصادرة ومحتوى فيسبوك (الريلز والروابط) لن تُرصد. الحل: إعدادات الهاتف → إمكانية الوصول → تفعيل FamilyMonitor"
                    !WhatsappNotificationService.listenerConnected ->
                        "خدمة قراءة الإشعارات غير مفعلة — رسائل واتساب وماسنجر الواردة لن تُرصد. الحل: إعدادات الهاتف → الوصول إلى الإشعارات → تفعيل FamilyMonitor"
                    else -> null
                }
                if (diag != null) {
                    lastDiagAt = System.currentTimeMillis()
                    try {
                        EventStore.addEvent(
                            this@MonitorService,
                            org.json.JSONObject()
                                .put("type", "APP")
                                .put("app", "تنبيه صيانة")
                                .put("packageName", "diagnostic")
                                .put("title", diag)
                        )
                    } catch (_: Exception) {}
                }
            }
            handler.postDelayed(this, 5_000)
        }
    }

    /** هل خدمة إمكانية الوصول مفعلة؟ (الرسائل الصادرة ومحتوى فيسبوك تعتمد عليها) */
    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val s = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            s.contains("com.family.monitor", ignoreCase = true)
        } catch (_: Exception) {
            true // عند عدم القدرة على الفحص نفترض أنها مفعلة حتى لا نزعج المستخدم
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            try {
                val server = Prefs.serverUrl(this@MonitorService)
                val key = Prefs.apiKey(this@MonitorService)
                if (server != null && key != null) Api.heartbeat(server, key)
            } catch (_: Exception) {}
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("MonitorThread")
        thread.start()
        handler = Handler(thread.looper)

        // مراقب سجل المكالمات
        callObserver = CallObserver(this, Handler(Looper.getMainLooper()))
        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callObserver!!)

        startForegroundWithNotification()
        ensureWhatsAppListener()
        handler.post(loop)
        handler.post(heartbeat)
    }

    @Deprecated("Deprecated in Java")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ضمان أن الخدمة أمامية في كل حالة (بعد إعادة التشغيل أو إعادة البدء التلقائي)
        startForegroundWithNotification()
        // يعيد التشغيل تلقائيًا لو قتل النظام الخدمة
        return START_STICKY
    }

    /**
     * إعادة ربط خدمة إشعارات واتساب بعد إعادة تشغيل الهاتف:
     * بعض الواجهات (MIUI وEMUI وغيرها) لا تعيد ربطها تلقائيًا بعد الإقلاع،
     * وهذه هي سبب اختفاء رسائل واتساب من اللوحة بعد الريستارت.
     */
    private fun ensureWhatsAppListener() {
        try {
            if (!Prefs.activated(this)) return
            val granted = androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(this).contains(packageName)
            if (granted && !WhatsappNotificationService.listenerConnected) {
                WhatsappNotificationService.requestRebindNow(this)
            }
        } catch (_: Exception) {}
    }

    private fun startForegroundWithNotification(customText: String? = null) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "monitor_channel"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_title),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        // الضغط على الإشعار الثابت = فتح شاشة «أذكاري» (أذكار الصباح والمساء)
        val openIntent = Intent(this, AdhkarActivity::class.java)
        val contentPi = android.app.PendingIntent.getActivity(
            this,
            1001,
            openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(customText ?: getString(R.string.notification_text))
            .setStyle(Notification.BigTextStyle()
                .bigText(customText ?: getString(R.string.notification_text)))
            .setContentIntent(contentPi)
            .setOngoing(true)
            .build()
        try {
            when {
                // أندرويد 14+: نوع specialUse — لأن dataSync ممنوع البدء من بث
                // إعادة التشغيل، ومحدود بـ 6 ساعات من أندرويد 15
                android.os.Build.VERSION.SDK_INT >= 34 -> startForeground(
                    1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                // أندرويد 9 - 13
                android.os.Build.VERSION.SDK_INT >= 29 -> startForeground(
                    1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                else -> startForeground(1, notification)
            }
        } catch (e: Exception) {
            // احتياطي لبعض الواجهات التي ترفض النوع — جرّب البدء بدون تحديد نوع
            try { startForeground(1, notification) } catch (_: Exception) {}
        }
    }

    /**
     * لو سُحب التطبيق من قائمة التطبيقات الأخيرة (بعض الواجهات تقتل الخدمة معه)
     * نجدول إعادة تشغيل الخدمة بعد 3 ثوانٍ عبر منبه بسيط.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val restartPi = android.app.PendingIntent.getBroadcast(
                this, 2002,
                Intent(this, BootReceiver::class.java).setAction(BootReceiver.ACTION_RESTART),
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            am.setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 3_000,
                restartPi
            )
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        callObserver?.let { contentResolver.unregisterContentObserver(it) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

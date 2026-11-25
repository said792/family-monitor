package com.family.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * مستقبِل الرمز السري من لوحة الاتصال.
 * عندما يكتب الأب في تطبيق الهاتف:  *#*#7594#*#*  ثم زر الاتصال،
 * يلتقط النظام الرمز محليًا (لا يذهب للشبكة) ويفتح شاشة التخفي.
 */
class DialReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == "android.provider.Telephony.SECRET_CODE") {
            val host = intent.data?.host ?: ""
            if (host == Config.SECRET_CODE_HOST) {
                val i = Intent(context, StealthActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(i)
            }
        }
    }
}

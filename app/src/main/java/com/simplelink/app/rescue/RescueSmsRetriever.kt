package com.simplelink.app.rescue

import android.content.Context
import com.google.android.gms.auth.api.phone.SmsRetriever

object RescueSmsRetriever {
    fun arm(context: Context) {
        runCatching {
            SmsRetriever.getClient(context.applicationContext)
                .startSmsRetriever()
        }
    }
}

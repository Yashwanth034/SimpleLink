package com.simplelink.app.rescue

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.simplelink.app.MainActivity
import com.simplelink.app.R
import com.simplelink.app.SimpleLinkApp
import com.simplelink.app.session.SessionStore

class RescueSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
        val status = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(SmsRetriever.EXTRA_STATUS, Status::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(SmsRetriever.EXTRA_STATUS) as? Status
        }
        if (status?.statusCode != CommonStatusCodes.SUCCESS) return

        val body = intent.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE) ?: return
        val sender = intent.getStringExtra(SmsRetriever.EXTRA_SMS_ORIGINATING_ADDRESS)
        val request = RescueProtocol.parse(body, sender) ?: return
        val activeCode = SessionStore(context).activeCode() ?: return
        if (request.code != activeCode) return

        (context.applicationContext as? SimpleLinkApp)?.sessionController?.incomingRescueRequest(request)
        notifyUser(context, request)
    }

    private fun notifyUser(context: Context, request: RescueRequest) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Rescue requests", NotificationManager.IMPORTANCE_HIGH)
        )
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CODE, request.code)
            putExtra(EXTRA_REQUEST_ID, request.requestId)
            putExtra(EXTRA_SENDER, request.senderAddress)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            request.requestId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SimpleLink rescue request")
            .setContentText("Open SimpleLink to allow or deny access")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(request.requestId.hashCode(), notification)
    }

    companion object {
        const val EXTRA_CODE = "rescue_code"
        const val EXTRA_REQUEST_ID = "rescue_request_id"
        const val EXTRA_SENDER = "rescue_sender"
        private const val CHANNEL_ID = "simplelink_rescue"
    }
}

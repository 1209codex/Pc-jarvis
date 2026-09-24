package com.jarvis.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        runCatching {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            val agent = CallAndSmsAgent.instance ?: context?.let(::CallAndSmsAgent)

            val combinedBySender = mutableMapOf<String, StringBuilder>()
            for (msg in messages) {
                val sender = msg.displayOriginatingAddress ?: continue
                val body = msg.displayMessageBody ?: continue
                combinedBySender.getOrPut(sender) { StringBuilder() }.append(body)
            }

            for ((sender, bodyBuilder) in combinedBySender) {
                val fullBody = bodyBuilder.toString()
                val otp = agent?.extractOtp(fullBody)
                com.jarvis.controlplane.JarvisEventBus.shared.post(
                    com.jarvis.controlplane.JarvisEvent.SmsReceived(sender, fullBody, otp)
                )
                agent?.onSmsReceived(sender, fullBody)
                Log.d(TAG, "Processed incoming SMS (length: ${fullBody.length}, hasOtp: ${otp != null})")
            }
        }.onFailure { e ->
            Log.w(TAG, "Error extracting incoming SMS: ${e.message}")
        }
    }
}

package com.jarvis.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class PhoneCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PhoneCallReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()

        Log.d(TAG, "Phone state changed: $stateStr, number: $incomingNumber")

        com.jarvis.controlplane.JarvisEventBus.shared.post(
            com.jarvis.controlplane.JarvisEvent.CallStateChanged(stateStr, incomingNumber)
        )

        val agent = CallAndSmsAgent.instance
        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val callerName = if (context != null && incomingNumber.isNotBlank()) {
                    runCatching { ContactResolver(context).resolveContact(incomingNumber)?.name }.getOrNull()
                } else null
                com.jarvis.controlplane.JarvisEventBus.shared.post(
                    com.jarvis.controlplane.JarvisEvent.IncomingCall(incomingNumber, callerName)
                )
                agent?.onIncomingCallRinging(incomingNumber) { announcement ->
                    Log.i(TAG, "Announcing incoming call: $announcement")
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                agent?.onCallEnded()
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.d(TAG, "Call off-hook (in conversation)")
            }
        }
    }
}

package com.jarvis.telecom

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.Q)
class JarvisCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "JarvisCallScreening"
        @Volatile
        var isSpamFilteringEnabled: Boolean = true
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val handle = callDetails.handle
        val rawNumber = handle?.schemeSpecificPart.orEmpty().trim()
        val isIncoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING

        Log.i(TAG, "Screening call: number='$rawNumber', isIncoming=$isIncoming")

        if (!isIncoming) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val contactResolver = ContactResolver(applicationContext)
        val contact = if (rawNumber.isNotBlank()) contactResolver.resolveContact(rawNumber) else null
        val callerName = contact?.name ?: rawNumber.ifBlank { "Unknown Caller" }

        val isSpam = isSpamFilteringEnabled && isKnownSpamPattern(rawNumber)

        val responseBuilder = CallResponse.Builder()

        if (isSpam) {
            Log.w(TAG, "Detected SPAM call from: $rawNumber. Silencing and rejecting.")
            responseBuilder
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(true)
        } else {
            responseBuilder
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(false)
                .setSkipNotification(false)
        }

        respondToCall(callDetails, responseBuilder.build())

        // Notify Jarvis control plane & CallAndSmsAgent
        val agent = CallAndSmsAgent.instance
        if (!isSpam && rawNumber.isNotBlank()) {
            agent?.onIncomingCallRinging(rawNumber) { announcement ->
                Log.i(TAG, "Call screening announcement ready: $announcement")
            }
        }
    }

    private fun isKnownSpamPattern(number: String): Boolean {
        val clean = number.filter { it.isDigit() }
        // Example spam heuristics: toll-free robocalls, repetitive digits, or 140-series telemarketing numbers in India
        if (clean.startsWith("140") && clean.length == 10) return true
        if (clean.startsWith("1800") && clean.length >= 10) return false // legitimate toll-free
        if (clean.length == 10 && clean.toSet().size <= 2) return true // fake numbers like 9999999999
        return false
    }
}

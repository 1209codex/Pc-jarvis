package com.jarvis.telecom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpExtractionTest {

    private val agent = CallAndSmsAgent(null)

    @Test
    fun testOtpExtraction_standardBankFormat() {
        val body = "Dear Customer, 492810 is your secret OTP for login to HDFC Bank NetBanking. Do not share it with anyone."
        val otp = agent.extractOtp(body)
        assertEquals("492810", otp)
    }

    @Test
    fun testOtpExtraction_keywordPrecedingColon() {
        val body = "Your Google verification code is: 849201. Never share this code."
        val otp = agent.extractOtp(body)
        assertEquals("849201", otp)
    }

    @Test
    fun testOtpExtraction_shortPin() {
        val body = "Your Uber login code is 1234. Valid for 5 minutes."
        val otp = agent.extractOtp(body)
        assertEquals("1234", otp)
    }

    @Test
    fun testOtpExtraction_eightDigitFormat() {
        val body = "Use OTP 19283746 to complete your transaction on Amazon."
        val otp = agent.extractOtp(body)
        assertEquals("19283746", otp)
    }

    @Test
    fun testOtpExtraction_noOtpPresent() {
        val body = "Hey bro, are we still meeting for lunch at 1pm tomorrow?"
        val otp = agent.extractOtp(body)
        assertNull(otp)
    }
}

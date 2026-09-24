package com.jarvis

import android.content.Context
import com.jarvis.bluetooth.BluetoothHeadsetManager
import com.jarvis.earbuds.EarbudsAssistantEngine
import org.junit.Assert.*
import org.junit.Test

class EarbudsAssistantTest {

    @Test
    fun testCleanReplyExtraction() {
        val btMgr = BluetoothHeadsetManager(null, null)
        val engine = EarbudsAssistantEngine(
            context = object : android.content.ContextWrapper(null) {},
            bluetoothHeadsetManager = btMgr,
            voiceEngine = null
        )

        assertEquals("I will reach in 10 minutes", engine.extractCleanReply("yes tell him I will reach in 10 minutes"))
        assertEquals("I am busy right now", engine.extractCleanReply("reply I am busy right now"))
        assertEquals("thank you", engine.extractCleanReply("say thank you"))
        assertEquals("okay coming", engine.extractCleanReply("okay coming"))
    }
}

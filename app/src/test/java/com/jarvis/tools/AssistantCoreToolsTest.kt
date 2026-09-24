package com.jarvis.tools

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class AssistantCoreToolsTest {

    private class MockContext : android.content.ContextWrapper(null) {
        private val tempDir = File(System.getProperty("java.io.tmpdir"), "jarvis_tools_test_${System.currentTimeMillis()}").apply { mkdirs() }
        override fun getFilesDir(): File = tempDir
    }

    private lateinit var context: Context

    @Before
    fun setup() {
        context = MockContext()
    }

    @Test
    fun testQuickNotesToolCrud() = runBlocking {
        val notesTool = QuickNotesTool(context)

        // 1. Add Note
        val addRes = notesTool.execute(mapOf(
            "action" to "add",
            "title" to "Grocery List",
            "content" to "Milk, Eggs, Bread",
            "tag" to "shopping"
        ))
        assertTrue("Add note should succeed", addRes.success)
        val noteId = (addRes as ToolResult.Success).data["id"]?.toString()
        assertNotNull(noteId)

        // 2. List Notes
        val listRes = notesTool.execute(mapOf("action" to "list", "tag" to "shopping"))
        assertTrue(listRes.success)
        assertTrue(listRes.message.contains("Milk, Eggs, Bread"))

        // 3. Search Notes
        val searchRes = notesTool.execute(mapOf("action" to "search", "query" to "Eggs"))
        assertTrue(searchRes.success)
        assertTrue(searchRes.message.contains("Milk, Eggs, Bread"))

        // 4. Delete Note
        val delRes = notesTool.execute(mapOf("action" to "delete", "id" to noteId!!))
        assertTrue(delRes.success)

        // 5. Verify deleted
        val afterDel = notesTool.execute(mapOf("action" to "list"))
        assertTrue(afterDel.message.contains("No notes"))
    }

    @Test
    fun testClockToolWorldClock() = runBlocking {
        val clockTool = ClockTool(context)
        val res = clockTool.execute(mapOf(
            "action" to "world_clock",
            "city" to "London"
        ))
        assertTrue(res.success)
        assertTrue(res.message.contains("Current time in London"))
    }

    @Test
    fun testTranslatorToolLanguageCodes() = runBlocking {
        val translator = TranslatorTool(context)
        val res = translator.execute(mapOf(
            "text" to "Hello",
            "target_lang" to "hi"
        ))
        assertTrue(res.success)
        assertNotNull((res as ToolResult.Success).data["target_lang"])
        assertEquals("hi", res.data["target_lang"])
    }

    @Test
    fun testWeatherToolExecution() = runBlocking {
        val weatherTool = WeatherTool(context)
        val res = weatherTool.execute(mapOf("city" to "Mumbai"))
        assertTrue(res.success)
        assertTrue(res.message.contains("weather", ignoreCase = true))
    }

    @Test
    fun testCalculatorToolExecution() = runBlocking {
        val calc = CalculatorTool(context)

        // 1. Basic operations
        val basic = calc.execute(mapOf("expression" to "25 * 4 + 10"))
        assertTrue(basic.success)
        assertTrue(basic.message.contains("110.0"))

        // 2. Parentheses & precedence
        val parens = calc.execute(mapOf("expression" to "2 * (3 + 4)"))
        assertTrue(parens.success)
        assertTrue(parens.message.contains("14.0"))

        // 3. Negative numbers
        val neg = calc.execute(mapOf("expression" to "-5 + 3"))
        assertTrue(neg.success)
        assertTrue(neg.message.contains("-2.0"))

        val negMul = calc.execute(mapOf("expression" to "10 * -2"))
        assertTrue(negMul.success)
        assertTrue(negMul.message.contains("-20.0"))

        // 4. Square root & absolute value
        val sqrtRes = calc.execute(mapOf("expression" to "sqrt(144)"))
        assertTrue(sqrtRes.success)
        assertTrue(sqrtRes.message.contains("12.0"))

        val sqrtAdd = calc.execute(mapOf("expression" to "sqrt(144) + 10"))
        assertTrue(sqrtAdd.success)
        assertTrue(sqrtAdd.message.contains("22.0"))

        val absRes = calc.execute(mapOf("expression" to "abs(-50) + 5"))
        assertTrue(absRes.success)
        assertTrue(absRes.message.contains("55.0"))
    }
}

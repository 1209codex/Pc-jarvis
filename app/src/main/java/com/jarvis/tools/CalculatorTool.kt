package com.jarvis.tools

import android.content.Context
import java.net.URLDecoder

class CalculatorTool(private val context: Context? = null) : Tool {
    override val name: String = "CALCULATE"
    override val description: String = "Evaluates mathematical expressions accurately. Parameter: expression (math string like '25 * 4 + 10' or 'sqrt(144)')."

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val expression = params["expression"] ?: return ToolResult(false, "Expression missing")

        return try {
            // Decode URL-encoded input (e.g., %2B -> +) safely without destroying '+' symbols
            val decoded = if (expression.contains("%")) {
                val preserved = expression.replace("+", "%2B")
                URLDecoder.decode(preserved, "UTF-8")
            } else {
                expression
            }
            val result = evaluate(decoded)
            ToolResult(true, "Result: $result")
        } catch (e: Exception) {
            ToolResult(false, "Cannot calculate: ${e.message}")
        }
    }

    private fun evaluate(expr: String): Double {
        val sanitized = expr.replace(" ", "").replace("x", "*").replace("×", "*").replace("÷", "/")

        if (sanitized.contains("(") || sanitized.contains("sqrt", ignoreCase = true) || sanitized.contains("abs", ignoreCase = true)) {
            return evaluateWithParens(sanitized)
        }

        return evaluateSimple(sanitized)
    }

    private fun evaluateWithParens(expr: String): Double {
        var e = expr
        while (e.contains("(")) {
            val start = e.lastIndexOf("(")
            val end = e.indexOf(")", start)
            if (end == -1) throw IllegalArgumentException("Mismatched parentheses")
            val inner = e.substring(start + 1, end)
            var result = evaluateSimple(inner)
            var replaceStart = start
            if (start >= 4 && e.substring(start - 4, start).equals("sqrt", ignoreCase = true)) {
                if (result < 0) throw IllegalArgumentException("Cannot calculate square root of negative number")
                result = Math.sqrt(result)
                replaceStart = start - 4
            } else if (start >= 3 && e.substring(start - 3, start).equals("abs", ignoreCase = true)) {
                result = Math.abs(result)
                replaceStart = start - 3
            }
            e = e.substring(0, replaceStart) + result + e.substring(end + 1)
        }
        return evaluateSimple(e)
    }

    // BUG-003 fix: Rewritten tokenizer to correctly handle negative numbers.
    // Uses a proper state-machine regex-based split instead of blind string replacement.
    private fun evaluateSimple(expr: String): Double {
        val tokens = tokenize(expr)

        val numbers = mutableListOf<Double>()
        val ops = mutableListOf<String>()

        for (token in tokens) {
            when (token) {
                "+", "-", "*", "/" -> ops.add(token)
                else -> numbers.add(token.toDouble())
            }
        }

        if (numbers.isEmpty()) throw IllegalArgumentException("No numbers")

        // First pass: handle * and /
        val newNumbers = mutableListOf(numbers[0])
        val newOps = mutableListOf<String>()

        for (i in ops.indices) {
            when (ops[i]) {
                "*" -> newNumbers[newNumbers.lastIndex] = newNumbers.last() * numbers[i + 1]
                "/" -> {
                    if (numbers[i + 1] == 0.0) throw ArithmeticException("Division by zero")
                    newNumbers[newNumbers.lastIndex] = newNumbers.last() / numbers[i + 1]
                }
                else -> {
                    newOps.add(ops[i])
                    newNumbers.add(numbers[i + 1])
                }
            }
        }

        // Second pass: handle + and -
        var result = newNumbers[0]
        for (i in newOps.indices) {
            when (newOps[i]) {
                "+" -> result += newNumbers[i + 1]
                "-" -> result -= newNumbers[i + 1]
            }
        }

        return result
    }

    /**
     * Tokenizes a math expression correctly handling negative numbers.
     * e.g. "-5+3"  -> ["-5", "+", "3"]
     *      "10*-2" -> ["10", "*", "-2"]
     */
    private fun tokenize(expr: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            // A '-' is a negative sign (not subtraction) if:
            // - it's at the start of the expression, OR
            // - the previous token was an operator
            if (c == '-' && (tokens.isEmpty() || tokens.last() in listOf("+", "-", "*", "/"))) {
                // Collect the negative number
                val sb = StringBuilder("-")
                i++
                while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                    sb.append(expr[i])
                    i++
                }
                tokens.add(sb.toString())
            } else if (c.isDigit() || c == '.') {
                val sb = StringBuilder()
                while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                    sb.append(expr[i])
                    i++
                }
                tokens.add(sb.toString())
            } else if (c == '+' || c == '-' || c == '*' || c == '/') {
                tokens.add(c.toString())
                i++
            } else {
                i++ // skip unexpected chars
            }
        }
        return tokens
    }
}

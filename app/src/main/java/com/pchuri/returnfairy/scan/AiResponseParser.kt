package com.pchuri.returnfairy.scan

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the raw text an on-device LLM produced for a book photo into
 * [ScannedBook]s. The model is asked for a JSON array of
 * `{"title": ..., "due": "YYYY-MM-DD" | null}` objects, but small models often
 * wrap it in markdown fences or prose, emit a bare object for a single book,
 * or invent key names — so this parser is deliberately forgiving.
 *
 * Pure Kotlin (org.json is part of the Android runtime) so it runs in JVM
 * unit tests without a device.
 */
object AiResponseParser {

    private val TITLE_KEYS = listOf("title", "book", "name", "book_title")
    private val DUE_KEYS = listOf("due", "due_date", "dueDate", "return_date", "returnDate")

    fun parse(response: String): List<ScannedBook> {
        val json = extractJson(response) ?: return emptyList()
        val objects = when (json) {
            is JSONArray -> (0 until json.length()).mapNotNull { json.optJSONObject(it) }
            is JSONObject -> listOf(json)
            else -> emptyList()
        }
        return objects
            .mapNotNull { toBook(it) }
            .distinctBy { it.title }
    }

    private fun toBook(obj: JSONObject): ScannedBook? {
        val title = TITLE_KEYS.firstNotNullOfOrNull { key ->
            obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
        }?.trim() ?: return null
        val due = DUE_KEYS.firstNotNullOfOrNull { key ->
            obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
        }?.let { ScanParser.findDate(it) }
        return ScannedBook(title = title, dueDate = due)
    }

    /**
     * Finds the first JSON array or object in the text, ignoring markdown
     * fences and any prose around it. Prefers an array (the requested shape).
     */
    private fun extractJson(text: String): Any? {
        val cleaned = text.replace("```json", "").replace("```", "")
        extractBalanced(cleaned, '[', ']')?.let { candidate ->
            runCatching { return JSONArray(candidate) }
        }
        extractBalanced(cleaned, '{', '}')?.let { candidate ->
            runCatching { return JSONObject(candidate) }
        }
        return null
    }

    private fun extractBalanced(text: String, open: Char, close: Char): String? {
        val start = text.indexOf(open)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}

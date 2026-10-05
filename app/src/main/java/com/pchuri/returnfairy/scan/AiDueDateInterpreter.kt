package com.pchuri.returnfairy.scan

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

sealed interface AiDueDateResult {
    data class Resolved(val date: LocalDate) : AiDueDateResult
    data object NoModel : AiDueDateResult
    data object NotUnderstood : AiDueDateResult
}

class AiDueDateInterpreter(private val context: Context) {

    private companion object {
        const val MAX_NUM_TOKENS = 2048
    }

    suspend fun interpret(phrase: String, today: LocalDate): AiDueDateResult = withContext(Dispatchers.IO) {
        val model = AiModelStore.modelFile(context) ?: return@withContext AiDueDateResult.NoModel
        val response = try {
            askModel(model, prompt(phrase, today), gpu = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            runCatching { askModel(model, prompt(phrase, today), gpu = false) }.getOrNull()
        } ?: return@withContext AiDueDateResult.NotUnderstood

        val date = AiDueDateResponseParser.parse(response, today)
            ?: return@withContext AiDueDateResult.NotUnderstood
        AiDueDateResult.Resolved(date)
    }

    private fun askModel(model: File, prompt: String, gpu: Boolean): String {
        var engine: Engine? = null
        try {
            val backend = if (gpu) Backend.GPU() else Backend.CPU()
            engine = Engine(
                EngineConfig(
                    modelPath = model.absolutePath,
                    backend = backend,
                    visionBackend = backend,
                    maxNumTokens = MAX_NUM_TOKENS,
                )
            )
            engine.initialize()
            engine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                )
            ).use { conversation ->
                val reply = conversation.sendMessage(Contents.of(Content.Text(prompt)))
                return reply.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
            }
        } finally {
            runCatching { engine?.close() }
        }
    }

    private fun prompt(phrase: String, today: LocalDate) = """
        Today is $today. Convert the user's book return-date phrase to one calendar date.
        Return only YYYY-MM-DD, strictly after today. If no single future date can be
        determined, return UNKNOWN. Never answer with today's date itself.
        User phrase: ${phrase.take(200)}
    """.trimIndent()
}

object AiDueDateResponseParser {
    private val dateOnly = Regex("\\d{4}-\\d{2}-\\d{2}")

    fun parse(response: String, today: LocalDate): LocalDate? {
        val value = dateOnly.matchEntire(response.trim())?.value ?: return null
        val date = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
        return date.takeIf { it.isAfter(today) && it <= today.plusYears(3) }
    }
}

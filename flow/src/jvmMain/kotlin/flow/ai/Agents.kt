package flow.ai

import flow.model.AI_ERR_NO_KEY
import flow.model.AI_ERR_NO_MODEL
import flow.model.AI_CLAUDE
import flow.model.AI_GEMINI
import flow.model.AI_OLLAMA
import flow.model.AI_OPENAI
import flow.model.AiReply
import flow.model.AiSetup
import flow.model.needsApiKey

/**
 * The HTTP assistants, as one thing the app talks to.
 *
 * Each provider gets its own agent because each holds its own transcripts; which one a turn goes to
 * is decided here rather than in the platform layer, so adding a fourth is a line in [byProvider]
 * and an AiApi beside the other three.
 */
internal object Agents {

    private val byProvider = mapOf(
        AI_OLLAMA to HttpAgent(OllamaApi),
        AI_OPENAI to HttpAgent(OpenAiApi),
        AI_GEMINI to HttpAgent(GeminiApi),
        AI_CLAUDE to HttpAgent(AnthropicApi),
    )

    private fun apiOf(provider: String): AiApi? = when (provider) {
        AI_OLLAMA -> OllamaApi
        AI_OPENAI -> OpenAiApi
        AI_GEMINI -> GeminiApi
        AI_CLAUDE -> AnthropicApi
        else -> null
    }

    fun handles(provider: String): Boolean = provider in byProvider

    fun ask(prompt: String, sessionId: String?, setup: AiSetup, systemPrompt: String, onText: (String) -> Unit): AiReply {
        val agent = byProvider[setup.provider] ?: return AiReply(error = "no assistant for '${setup.provider}'")
        val api = apiOf(setup.provider)!!
        if (needsApiKey(setup.provider, setup.transport) && setup.apiKey.isBlank()) return AiReply(error = AI_ERR_NO_KEY)

        // A blank model means "whatever this server has": asking it is better than guessing a name,
        // and on a machine with one model that is the one meant.
        val model = setup.model.ifBlank {
            api.models(setup.url, setup.apiKey).firstOrNull() ?: return AiReply(error = AI_ERR_NO_MODEL)
        }
        return agent.ask(prompt, sessionId, Call(setup.url, model, setup.apiKey, systemPrompt), onText)
    }

    /** What [setup]'s server offers. Empty when it cannot be reached, or has nothing to offer. */
    fun models(setup: AiSetup): List<String> {
        if (needsApiKey(setup.provider, setup.transport) && setup.apiKey.isBlank()) return emptyList()
        return apiOf(setup.provider)?.models(setup.url, setup.apiKey).orEmpty()
    }

    /** Stops whichever is running. Only one can be, so the rest are no-ops. */
    fun stop() = byProvider.values.forEach { it.stop() }

    fun forget(sessionId: String?) = byProvider.values.forEach { it.forget(sessionId) }
}

package flow.core

import flow.model.AI_GEMINI
import flow.model.AI_PROVIDERS
import flow.model.AI_OLLAMA
import flow.model.AiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An answer remembers which assistant gave it.
 *
 * The panel used to print "CLAUDE" over every answer, whoever had actually written it — a fixed
 * string from when there was only one. Reading the current provider instead would be the same bug
 * one step along: the conversation stays on screen when the provider is switched, so the label has
 * to belong to the message.
 */
class AiSpeakerTest {

    private fun workspace() = Workspace(CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `an answer carries the provider that was in force when it was asked for`() {
        val ws = workspace()
        ws.aiProvider = AI_OLLAMA
        // what askAi puts in the transcript before the reply arrives, without running a turn
        ws.aiMessages = listOf(
            AiMessage(fromUser = true, text = "hello"),
            AiMessage(fromUser = false, text = "hi", provider = ws.aiProvider),
        )

        // switching provider starts a new conversation but leaves this one on screen
        ws.aiProvider = AI_GEMINI

        assertEquals(AI_OLLAMA, ws.aiMessages.last().provider, "the answer was relabelled by a later switch")
    }

    @Test
    fun `a user's own line has no provider on it`() {
        assertEquals("", AiMessage(fromUser = true, text = "hello").provider)
    }

    /**
     * Every provider has a name for the label over its answers.
     *
     * The panel's title is just "AI" — which one is answering is already on each reply and on the
     * model picker — but a provider with no name here would leave those replies unattributed.
     */
    @Test
    fun `every provider has a name to show`() {
        val ws = workspace()
        AI_PROVIDERS.forEach { (id, label) ->
            ws.aiProvider = id
            assertEquals(label, ws.aiProviderName, "no name for '$id'")
        }
    }

    @Test
    fun `a provider from a newer build shows its id rather than nothing`() {
        val ws = workspace()
        ws.aiProvider = "something-else"
        assertEquals("something-else", ws.aiProviderName)
    }
}

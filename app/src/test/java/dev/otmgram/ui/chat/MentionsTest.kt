package dev.otmgram.ui.chat

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionsTest {

    private fun candidate(name: String, username: String? = null) = MentionCandidate(1, name, username, null, null)

    @Test
    fun `an @ at the start or after a space starts a mention, not one inside a word`() {
        assertEquals("", mentionQuery("@"))
        assertEquals("an", mentionQuery("hey @an"))
        assertEquals("Лена", mentionQuery("привет @Лена"))
        assertNull(mentionQuery("mail me at a@b"))
        assertNull(mentionQuery("@anna "))
        assertNull(mentionQuery("no at all"))
    }

    @Test
    fun `the mention replaces what was typed after the @`() {
        assertEquals("hey @anna_k ", insertMention("hey @an", candidate("Anna", "anna_k")))
        assertEquals("Anna ", insertMention("@", candidate("Anna")))
    }

    @Test
    fun `names are linked where they still are, each once, in order`() {
        val entities = mentionEntities("Anna and Tom, Anna", listOf(NamedMention(1, "Anna"), NamedMention(2, "Tom"), NamedMention(3, "Anna"), NamedMention(4, "Lea")))
        assertEquals(listOf(0 to 1L, 9 to 2L, 14 to 3L), entities.map { it.offset to (it.type as TdApi.TextEntityTypeMentionName).userId })
    }
}

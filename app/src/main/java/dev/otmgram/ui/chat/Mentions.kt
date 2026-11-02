package dev.otmgram.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TdException
import dev.otmgram.ui.theme.ChatAvatar
import dev.otmgram.ui.theme.Hairline
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/** A group member to mention: by [username] if they have one, else by [name], linked to their id. */
@Immutable
data class MentionCandidate(
    val userId: Long,
    val name: String,
    val username: String?,
    val photoFileId: Int?,
    val minithumbnail: ByteArray?,
)

/** A mention by name put in the text: [name], to link to [userId] when sent. */
data class NamedMention(val userId: Long, val name: String)

/** What's typed after an "@" at the end of [text] (which may be nothing yet), or null if it doesn't end in one. */
internal fun mentionQuery(text: String): String? = MENTION_AT_END.find(text)?.groupValues?.get(1)

/** [text] with the "@…" at its end replaced by the mention, and a space to go on typing. */
internal fun insertMention(text: String, candidate: MentionCandidate): String {
    val at = MENTION_AT_END.find(text) ?: return text
    val start = at.range.first + at.value.indexOf('@')
    return text.substring(0, start) + (candidate.username?.let { "@$it" } ?: candidate.name) + " "
}

/**
 * Links each mention by name to its person where its name still is in [text], in order, one
 * occurrence each; names deleted since are left out. Mentions by username need nothing:
 * Telegram finds "@username" in the text itself.
 */
internal fun mentionEntities(text: String, mentions: List<NamedMention>): Array<TdApi.TextEntity> {
    var from = 0
    return mentions.mapNotNull { mention ->
        val at = text.indexOf(mention.name, from).takeIf { it >= 0 } ?: text.indexOf(mention.name).takeIf { it >= 0 } ?: return@mapNotNull null
        from = at + mention.name.length
        TdApi.TextEntity(at, mention.name.length, TdApi.TextEntityTypeMentionName(mention.userId))
    }.distinctBy { it.offset }.sortedBy { it.offset }.toTypedArray()
}

/** "@" followed by a name's letters, at the start or after a space, at the end of the text. */
private val MENTION_AT_END = Regex("""(?:^|\s)@([\p{L}\p{N}_]*)$""")

/**
 * The members to mention while an "@…" is being typed in a group, matching what follows it;
 * empty otherwise.
 */
class MentionPicker(
    private val scope: CoroutineScope,
    /** The group's members matching a query (all, for an empty one); nothing outside a group. */
    private val search: suspend (query: String) -> List<MentionCandidate>,
) {
    var candidates by mutableStateOf<List<MentionCandidate>>(emptyList())
        private set

    private var query: String? = null
    private var searching: Job? = null

    /** Follows what's typed. */
    fun update(text: String) {
        val next = mentionQuery(text)
        if (next == query) return
        query = next
        searching?.cancel()
        if (next == null) {
            candidates = emptyList()
            return
        }
        searching = scope.launch {
            delay(MENTION_SEARCH_DELAY_MS)
            candidates = try {
                search(next)
            } catch (e: TdException) {
                AppLog.w("MentionPicker", "Finding members failed", e)
                emptyList()
            }
        }
    }

    /** Picked or typed past: the list goes until the next "@". */
    fun dismiss() {
        searching?.cancel()
        candidates = emptyList()
    }
}

private const val MENTION_SEARCH_DELAY_MS = 120L

/** Over the input bar while mentioning: who matches, a tap puts them in. */
@Composable
internal fun MentionList(candidates: List<MentionCandidate>, onPick: (MentionCandidate) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Otm.Sheet)) {
        Hairline()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = MENTION_LIST_MAX).testTag("mentions")) {
            items(candidates, key = { it.userId }) { candidate ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onPick(candidate) }
                        .testTag("mention-${candidate.userId}")
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ChatAvatar(candidate.userId, candidate.name, candidate.photoFileId, candidate.minithumbnail, size = 34.dp)
                    Text(candidate.name, style = OtmType.Name, color = Otm.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    candidate.username?.let { Text("@$it", style = OtmType.Secondary, color = Otm.InkSoft, maxLines = 1) }
                }
            }
        }
    }
}

private val MENTION_LIST_MAX = 220.dp

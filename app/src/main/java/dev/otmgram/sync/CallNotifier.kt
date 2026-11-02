package dev.otmgram.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.MainActivity
import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.core.launchLogged
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.data.AppSettings
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.NotificationMode
import dev.otmgram.data.fullName
import dev.otmgram.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rings for a Telegram call from someone of the folder. Otmgram can't take calls (that needs
 * Telegram's own call engine), so it says who's calling and offers to answer in Telegram, if
 * installed, or to decline. The ringing stops as the call is answered elsewhere, declined or
 * given up; TDLib hears of it whenever the app is connected (always, with Instant notifications).
 */
@Singleton
class CallNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TelegramClient,
    private val folders: FolderRepository,
    private val settings: AppSettings,
    private val strings: Strings,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Calls ringing now, by id. */
    private val ringing: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Subscribes to updates; before TDLib starts, like [NotificationCenter]. */
    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update ->
                if (update is TdApi.UpdateCall) scope.launchLogged(TAG) { onCall(update.call) }
            }
        }
    }

    private suspend fun onCall(call: TdApi.Call) {
        val incoming = !call.isOutgoing && call.state is TdApi.CallStatePending
        if (!incoming) {
            if (ringing.remove(call.id)) NotificationManagerCompat.from(context).cancel(TAG, call.id)
            return
        }
        if (call.id in ringing) return
        // As for messages: only the folder's people (a private chat's id is the user's), and not with notifications off.
        if (!folders.isInFolder(call.userId) || settings.notificationMode.first() == NotificationMode.Off) return
        val name = try {
            td.send(TdApi.GetUser(call.userId)).fullName()
        } catch (e: TdException) {
            ""
        }
        ringing += call.id
        AppLog.i(TAG, "Incoming call, ringing") // Never who.
        show(call, name.ifEmpty { strings.get(R.string.call_someone) })
    }

    private fun show(call: TdApi.Call, name: String) {
        createChannel()
        val telegram = telegramApp()
        val open = telegram?.let { PendingIntent.getActivity(context, call.id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            ?: openChat(call.userId)
        val decline = PendingIntent.getBroadcast(
            context,
            call.id,
            Intent(context, Decline::class.java).putExtra(EXTRA_CALL, call.id).putExtra(EXTRA_VIDEO, call.isVideo),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = strings.get(if (call.isVideo) R.string.call_video_from else R.string.call_from, name)
        val text = strings.get(if (telegram != null) R.string.call_answer_in_telegram_hint else R.string.call_answer_elsewhere)
        // The lock screen says a call is coming, not whose.
        val locked = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(strings.get(R.string.call_incoming))
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(locked)
            .setOngoing(true)
            // Telegram gives up on an unanswered call after about a minute; this in case nothing says so.
            .setTimeoutAfter(RING_MAX_MS)
            .setContentIntent(open)
            .addAction(0, strings.get(R.string.call_decline), decline)
        if (telegram != null) builder.addAction(0, strings.get(R.string.call_answer_in_telegram), open)
        // Rings on, like a phone, until the call stops ringing.
        val notification = builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
        try {
            NotificationManagerCompat.from(context).notify(TAG, call.id, notification)
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Can't ring: notifications aren't allowed", e)
        }
    }

    /** The official app (or another Telegram app) to answer in; null if none is installed. */
    private fun telegramApp(): Intent? = TELEGRAM_APPS.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun openChat(userId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .setData("otmgram://chat/$userId".toUri())
            .putExtra(MainActivity.EXTRA_OPEN_CHAT, userId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val system = context.getSystemService(NotificationManager::class.java)
        if (system.getNotificationChannel(CHANNEL_ID) != null) return
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, strings.get(R.string.calls_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
            },
        )
    }

    /** Declines a call from its notification. */
    @AndroidEntryPoint
    class Decline : BroadcastReceiver() {

        @Inject lateinit var td: TelegramClient

        @Inject @ApplicationScope lateinit var scope: CoroutineScope

        override fun onReceive(context: Context, intent: Intent) {
            val callId = intent.getIntExtra(EXTRA_CALL, 0)
            NotificationManagerCompat.from(context).cancel(TAG, callId)
            val pending = goAsync()
            scope.launch {
                try {
                    td.send(TdApi.DiscardCall(callId, false, "", 0, intent.getBooleanExtra(EXTRA_VIDEO, false), 0))
                } catch (e: TdException) {
                    AppLog.w(TAG, "Declining a call failed", e)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private companion object {
        const val TAG = "CallNotifier"
        const val CHANNEL_ID = "calls"
        const val EXTRA_CALL = "dev.otmgram.extra.CALL"
        const val EXTRA_VIDEO = "dev.otmgram.extra.VIDEO"
        const val RING_MAX_MS = 90_000L

        /** Telegram's apps, the official one first; the manifest lists them to be found. */
        val TELEGRAM_APPS = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "org.telegram.messenger.beta")
    }
}

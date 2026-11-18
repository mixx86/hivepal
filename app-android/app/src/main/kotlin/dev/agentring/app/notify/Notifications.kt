package dev.agentring.app.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.app.ui.MainActivity
import dev.agentring.core.Conv
import dev.agentring.core.Message

object Notifications {
    const val CHANNEL_MESSAGES = "messages"
    const val ID_MESSAGES = 1001
    const val ID_TEST = 1002
    const val ID_REPLY_FAILED = 1003
    const val KEY_REPLY = "reply_text"
    const val EXTRA_CONV = "conv"
    const val EXTRA_OPTION = "option"
    const val EXTRA_REPLY_TO = "reply_to"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun mutableFlag() = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    /** Opens the app on a conversation (or on the chat list when `conv` is null). */
    fun openMessagesIntent(context: Context, conv: String? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MESSAGES)
            .putExtra(MainActivity.EXTRA_CONV, conv)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode(conv), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun requestCode(conv: String?) = (conv ?: "").hashCode()

    /** One notification per conversation. `main`'s keeps the original id. */
    fun notificationId(conv: String): Int = if (conv == Conv.MAIN) ID_MESSAGES else 2000 + (conv.hashCode() and 0xFFFF)

    private fun replyAction(context: Context, conv: String): NotificationCompat.Action {
        val intent = Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_CONV, conv)
        val pi = PendingIntent.getBroadcast(context, requestCode(conv), intent, PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag())
        val input = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.reply)).build()
        return NotificationCompat.Action.Builder(R.drawable.ic_send, context.getString(R.string.reply), pi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    /** A quick reply the agent offered, as a notification button: it sends that text as a reply. */
    private fun optionAction(context: Context, conv: String, replyTo: String, option: String, index: Int): NotificationCompat.Action {
        val intent = Intent(context, ReplyReceiver::class.java)
            .putExtra(EXTRA_CONV, conv)
            .putExtra(EXTRA_OPTION, option)
            .putExtra(EXTRA_REPLY_TO, replyTo)
        val pi = PendingIntent.getBroadcast(
            context, requestCode(conv) * 8 + index + 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_send, option, pi).setShowsUserInterface(false).build()
    }

    private fun agentPerson(name: String, key: String = "agent") = Person.Builder().setName(name).setKey(key).build()

    /**
     * Shows (or updates) a conversation's notification with the agents' unread
     * messages, plus an optional reply the user just sent from the notification.
     */
    @SuppressLint("MissingPermission")
    fun showMessages(
        context: Context,
        conv: String = Conv.MAIN,
        agentMessages: List<Message> = Graph.messages.unreadFromAgent(conv),
        sentReply: String? = null,
    ) {
        if (!canNotify(context)) return
        val unread = agentMessages.takeLast(6)
        if (unread.isEmpty() && sentReply == null) return
        val main = Graph.mainName
        val me = Person.Builder().setName(context.getString(R.string.you)).build()
        val style = NotificationCompat.MessagingStyle(me)
        if (Conv.isGroup(conv)) {
            style.conversationTitle = Graph.directory.convName(conv, main)
            style.isGroupConversation = true
        }
        unread.forEach {
            val author = it.author ?: Conv.MAIN_AGENT
            style.addMessage(it.text, it.createdAt, agentPerson(Graph.directory.nameOf(author, main), author))
        }
        if (sentReply != null) style.addMessage(sentReply, System.currentTimeMillis(), null as Person?)

        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif_message)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openMessagesIntent(context, conv))
            .setAutoCancel(true)
            .setOnlyAlertOnce(sentReply != null)
            .addAction(replyAction(context, conv))
            .apply {
                // The newest message's quick replies (if any) as buttons; at most 3 fit next to "Reply".
                val last = unread.lastOrNull()
                if (sentReply == null && last != null) {
                    last.options.orEmpty().take(3).forEachIndexed { i, o -> addAction(optionAction(context, conv, last.msgId, o, i)) }
                }
            }
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(conv), n)
    }

    @SuppressLint("MissingPermission")
    fun showReplyFailed(context: Context, text: String, error: String) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif_message)
            .setContentTitle(context.getString(R.string.reply_failed_title))
            .setContentText("$text — $error")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n$error"))
            .setContentIntent(openMessagesIntent(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_REPLY_FAILED, n)
    }

    @SuppressLint("MissingPermission")
    fun showTest(context: Context) {
        if (!canNotify(context)) return
        val name = Graph.pairing.current?.agentName ?: context.getString(R.string.app_name)
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(context.getString(R.string.you)).build())
            .addMessage(context.getString(R.string.test_notification_text), System.currentTimeMillis(), agentPerson(name))
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif_message)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_TEST, n)
    }

    /** Cancels one conversation's notification, or all of them. */
    fun cancelMessages(context: Context, conv: String? = null) {
        val nm = NotificationManagerCompat.from(context)
        if (conv != null) {
            nm.cancel(notificationId(conv))
            return
        }
        nm.cancel(ID_MESSAGES)
        for (n in nm.activeNotifications) if (n.id in 2000..(2000 + 0xFFFF)) nm.cancel(n.id)
    }
}

package dev.agentring.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.app.ui.MainActivity

/** Home-screen widget (Hivepal): the latest message from an agent, unread count, and a call button. */
class HivepalWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    companion object {
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HivepalWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val messages = Graph.messages.messages.value.filter { !it.convId.startsWith("bots:") }
            val last = messages.lastOrNull { it.fromAgent }
            val unread = messages.count { it.fromAgent && it.readAt == null }
            val main = Graph.mainName
            val views = RemoteViews(context.packageName, R.layout.widget_hivepal)
            views.setTextViewText(R.id.widget_unread, if (unread > 0) "$unread" else "")
            if (last != null) {
                val who = Graph.directory.nameOf(last.author, main)
                val where = if (last.convId.startsWith("group:")) " · " + Graph.directory.convName(last.convId, main) else ""
                views.setTextViewText(R.id.widget_from, who + where)
                views.setTextViewText(R.id.widget_text, last.text.ifBlank { "📎" }.replace(Regex("[*_`#>]"), ""))
            } else {
                views.setTextViewText(R.id.widget_from, "")
                views.setTextViewText(R.id.widget_text, context.getString(R.string.widget_empty))
            }
            val open = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MESSAGES)
                .putExtra(MainActivity.EXTRA_CONV, last?.convId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 31, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            val call = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_CALL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setOnClickPendingIntent(
                R.id.widget_call,
                PendingIntent.getActivity(context, 32, call, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            manager.updateAppWidget(ids, views)
        }
    }
}

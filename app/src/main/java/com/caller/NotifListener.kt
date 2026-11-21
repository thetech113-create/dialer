package com.caller

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * Mirrors the phone's notifications to the PC once the user grants notification access, and only while PC Link is
 * on: with PC Link off the app unbinds, so it receives no other app's notifications (see [follow]).
 */
class NotifListener : NotificationListenerService() {
    private val lastSent = HashMap<String, Int>()

    override fun onListenerConnected() {
        if (!LinkState.running) {
            requestUnbind()
            return
        }
        instance = this
        sendActive()
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val msg = toMessage(sbn) ?: return
        val digest = msg.optString("title").hashCode() * 31 + msg.optString("text").hashCode()
        synchronized(lastSent) {
            if (lastSent[sbn.key] == digest) return
            if (lastSent.size > 500) lastSent.clear()
            lastSent[sbn.key] = digest
        }
        LinkState.send(msg)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val known = synchronized(lastSent) { lastSent.remove(sbn.key) != null }
        if (known) LinkState.send(JSONObject().put("t", "notif_removed").put("key", sbn.key))
    }

    /** Sends everything currently in the shade; used when a PC connects. */
    fun sendActive() {
        val current = try {
            activeNotifications
        } catch (e: RuntimeException) {
            null
        } ?: return
        synchronized(lastSent) { lastSent.clear() }
        current.sortedBy { it.postTime }.forEach { onNotificationPosted(it) }
    }

    fun dismiss(key: String) {
        try {
            cancelNotification(key)
        } catch (_: RuntimeException) {
        }
    }

    private fun toMessage(sbn: StatusBarNotification): JSONObject? {
        if (sbn.packageName == packageName || sbn.isOngoing) return null
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        return JSONObject()
            .put("t", "notif")
            .put("key", sbn.key)
            .put("pkg", sbn.packageName)
            .put("app", appLabel(sbn.packageName))
            .put("title", title)
            .put("text", text.take(MAX_TEXT))
            .put("time", sbn.postTime)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        private const val MAX_TEXT = 2000

        @Volatile
        var instance: NotifListener? = null
            private set

        /** PC Link started or stopped: receive notifications only while it runs. */
        fun follow(context: Context, linkRunning: Boolean) {
            if (linkRunning) {
                runCatching { requestRebind(ComponentName(context, NotifListener::class.java)) }
            } else {
                runCatching { instance?.requestUnbind() }
            }
        }
    }
}

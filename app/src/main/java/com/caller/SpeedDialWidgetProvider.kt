package com.caller

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.widget.RemoteViews

class SpeedDialWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences("speed_dial_widgets", Context.MODE_PRIVATE)
            val contactName = prefs.getString("name_$appWidgetId", "Unknown") ?: "Unknown"
            val phoneNumber = prefs.getString("number_$appWidgetId", null)

            val views = RemoteViews(context.packageName, R.layout.widget_speed_dial)
            
            // Format name
            val shortName = contactName.split(" ").firstOrNull() ?: contactName
            views.setTextViewText(R.id.widget_name, shortName)

            // --- CRITICAL ADDITION: Draw the App-Style Avatar ---
            if (contactName != "Unknown") {
                val avatarBitmap = createAvatarBitmap(context, contactName)
                views.setImageViewBitmap(R.id.widget_icon, avatarBitmap)
            } else {
                views.setImageViewResource(R.id.widget_icon, android.R.drawable.ic_menu_call)
            }

            if (phoneNumber != null) {
                val callIntent = Intent(context, WidgetCallActivity::class.java).apply {
                    action = Constants.ACTION_WIDGET_CALL
                    putExtra(Constants.EXTRA_WIDGET_PHONE_NUMBER, phoneNumber)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                
                val pendingIntent = PendingIntent.getActivity(
                    context, 
                    appWidgetId, 
                    callIntent, 
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                
                views.setOnClickPendingIntent(R.id.widget_icon, pendingIntent)
                views.setOnClickPendingIntent(R.id.widget_name, pendingIntent)
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        // --- HELPER FUNCTIONS TO DRAW THE COLORED CIRCLE ---

        private fun getAvatarColor(name: String): Int {
            val colors = listOf(
                0xFF1ABC9C, 0xFF2ECC71, 0xFF3498DB,
                0xFF9B59B6, 0xFF34495E, 0xFF16A085,
                0xFF27AE60, 0xFF2980B9, 0xFF8E44AD,
                0xFF2C3E50, 0xFFF1C40F, 0xFFE67E22,
                0xFFE74C3C, 0xFFD35400, 0xFFC0392B
            )
            if (name.isEmpty() || name == "Unknown") return colors[0].toInt()
            // Same key/hash as MainActivity.getAvatarColor for saved contacts, so colors match the app.
            val key = "name:" + name.trim().lowercase()
            return colors[Math.floorMod(key.hashCode(), colors.size)].toInt()
        }

        private fun createAvatarBitmap(context: Context, name: String): Bitmap {
            // CRITICAL FIX: Reduced to 48dp to fit properly in 1x1 widget
            val sizePx = (48 * context.resources.displayMetrics.density).toInt()
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Draw colored circle
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = getAvatarColor(name)
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

            // Draw first letter
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = sizePx * 0.5f 
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }

            val firstLetter = if (name.isNotEmpty()) name.first().uppercase() else "?"
            
            // Vertically center the text
            val textY = (sizePx / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
            
            canvas.drawText(firstLetter, sizePx / 2f, textY, textPaint)
            
            return bitmap
        }
    }
}

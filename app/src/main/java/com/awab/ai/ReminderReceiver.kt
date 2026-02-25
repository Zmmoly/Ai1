package com.awab.ai

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderManager.ACTION_REMINDER -> fireReminder(context, intent)
            Intent.ACTION_BOOT_COMPLETED -> rescheduleAll(context)
        }
    }

    private fun fireReminder(context: Context, intent: Intent) {
        val reminderId = intent.getIntExtra(ReminderManager.EXTRA_REMINDER_ID, -1)
        val reminderText = intent.getStringExtra(ReminderManager.EXTRA_REMINDER_TEXT) ?: "تذكير!"

        // إنشاء قناة الإشعارات
        ReminderManager.createNotificationChannel(context)

        // Intent لفتح التطبيق عند الضغط على الإشعار
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, reminderId, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // بناء الإشعار
        val notification = NotificationCompat.Builder(context, ReminderManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⏰ تذكير من أواب AI")
            .setContentText(reminderText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminderText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(reminderId, notification)

        // حذف التذكير من القائمة بعد تنفيذه
        if (reminderId != -1) {
            ReminderManager.deleteReminder(context, reminderId)
        }

        // اهتزاز
        vibrate(context)
    }

    private fun vibrate(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 500, 200, 500), -1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // إعادة جدولة التذكيرات بعد إعادة تشغيل الجهاز
    private fun rescheduleAll(context: Context) {
        val pending = ReminderManager.getPendingReminders(context)
        for (reminder in pending) {
            ReminderManager.scheduleAlarm(context, reminder)
        }
    }
}

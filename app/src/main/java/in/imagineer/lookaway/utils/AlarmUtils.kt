package `in`.imagineer.lookaway.utils

import kotlinx.coroutines.*
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.*
import `in`.imagineer.lookaway.receiver.NotificationReceiver

object AlarmUtils {
    fun startReminder(context: Context, preferenceManager: PreferenceManager) {
        val intervalMillis = preferenceManager.intervalMinutes * 60 * 1000L
        val nextTriggerTime = getNextValidTriggerTime(preferenceManager, intervalMillis)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            nextTriggerTime,
            pendingIntent
        )

        preferenceManager.nextTriggerTime = nextTriggerTime
    }

    fun stopReminder(context: Context, preferenceManager: PreferenceManager) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)

        preferenceManager.remove(preferenceManager.keys.NEXT_TRIGGER_TIME)
    }

    fun getNextValidTriggerTime(
        preferenceManager: PreferenceManager,
        intervalMillis: Long
    ): Long {
        val startHour = preferenceManager.startHour
        val startMinute = preferenceManager.startMinute
        val endHour = preferenceManager.endHour
        val endMinute = preferenceManager.endMinute
        val enabledDays = preferenceManager.enabledDays
        val currentTime = Calendar.getInstance()
        val currentDay = currentTime.get(Calendar.DAY_OF_WEEK)

        val startTimeMinutes = startHour * 60 + startMinute
        val endTimeMinutes = endHour * 60 + endMinute

        val todayStartTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startHour)
            set(Calendar.MINUTE, startMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val todayEndTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, endHour)
            set(Calendar.MINUTE, endMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (endTimeMinutes <= startTimeMinutes) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val isTodayEnabled = currentDay in enabledDays

        if (isTodayEnabled &&
            currentTime.timeInMillis >= todayStartTime.timeInMillis &&
            currentTime.timeInMillis < todayEndTime.timeInMillis
        ) {
            val nextTriggerTime = currentTime.timeInMillis + intervalMillis

            if (nextTriggerTime <= todayEndTime.timeInMillis) {
                return SystemClock.elapsedRealtime() + intervalMillis
            }
        }

        // check if still inside yesterday's schedule
        // needed for schedules that continue past midnight (eg. from 22:00 Monday (enabled) to 04:00 Tuesday (not enabled))
        val yesterdayStartTime = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, startHour)
            set(Calendar.MINUTE, startMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val yesterdayEndTime = Calendar.getInstance().apply {
            timeInMillis = yesterdayStartTime.timeInMillis
            set(Calendar.HOUR_OF_DAY, endHour)
            set(Calendar.MINUTE, endMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (endTimeMinutes <= startTimeMinutes) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        if (yesterdayStartTime.get(Calendar.DAY_OF_WEEK) in enabledDays &&
            currentTime.timeInMillis >= yesterdayStartTime.timeInMillis &&
            currentTime.timeInMillis < yesterdayEndTime.timeInMillis
        ) {
            val nextTriggerTime = currentTime.timeInMillis + intervalMillis

            if (nextTriggerTime <= yesterdayEndTime.timeInMillis) {
                return SystemClock.elapsedRealtime() + intervalMillis
            }
        }

        if (isTodayEnabled && currentTime.timeInMillis < todayStartTime.timeInMillis) {
            return SystemClock.elapsedRealtime() +
                (todayStartTime.timeInMillis - currentTime.timeInMillis)
        }

        val nextStartTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startHour)
            set(Calendar.MINUTE, startMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }

        for (i in 0 until 7) {
            if (nextStartTime.get(Calendar.DAY_OF_WEEK) in enabledDays) break
            nextStartTime.add(Calendar.DAY_OF_YEAR, 1)
        }

        return SystemClock.elapsedRealtime() +
            (nextStartTime.timeInMillis - currentTime.timeInMillis)
    }

    fun startCountdown(
        preferenceManager: PreferenceManager,
        onTick: (Long) -> Unit,
    ): Job {
        var remainingTime = preferenceManager.nextTriggerTime - SystemClock.elapsedRealtime()

        return CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(1000)
                remainingTime -= 1000

                if (remainingTime <= 0) {
                    val newTriggerTime = getNextValidTriggerTime(
                        preferenceManager,
                        preferenceManager.intervalMinutes * 60 * 1000L
                    )
                    preferenceManager.nextTriggerTime = newTriggerTime
                    remainingTime = newTriggerTime - SystemClock.elapsedRealtime()
                }

                onTick(remainingTime)
            }
        }
    }
}

package com.b50.roast.data

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.b50.roast.MainActivity
import com.b50.roast.R

/**
 * 出图通知：进度（常驻、静音）+ 完成/失败（会响一声）。
 *
 * 为什么要有它：出图要 20~60 秒（AI 写作占大头），用户点完就该能干别的。
 * 配合 `service/RenderKeepAlive`（前台服务）一起用 —— 前台服务保证进程不被 MIUI 冻结，
 * 通知负责"让用户随时知道进度、完成时被提醒一下"。
 *
 * 两个渠道分开是必须的：进度渠道用 `IMPORTANCE_LOW`（静音、不打扰、可常驻），
 * 完成渠道用 `IMPORTANCE_DEFAULT`（会响一声）。**渠道的音量/重要性只能由用户在系统设置里改，
 * 代码里事后改 importance 是无效的** —— 所以一开始就要分两条，别合并。
 */
object RoastNotify {

    const val ID_PROGRESS = 1001      // 前台服务用这个 id（startForeground 必须固定一个）
    const val ID_DONE = 1002

    private const val CH_PROGRESS = "b50_render_progress"
    private const val CH_DONE = "b50_render_done"

    /** 当前进度文案（service 重新拉起时要用它复原通知，避免闪一下"正在准备…"） */
    @Volatile
    var currentText: String = "正在准备…"
        private set

    // ---------------------------------------------------------------- 渠道

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH_PROGRESS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "出图进度", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "出图过程中的常驻进度条（静音，不会打扰你）"
                    setShowBadge(false)
                    enableVibration(false)
                }
            )
        }
        if (nm.getNotificationChannel(CH_DONE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "出图完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "出图完成或失败时的提醒"
                }
            )
        }
    }

    // ---------------------------------------------------------------- 通知内容

    /** 进度通知（前台服务也要用同一份，所以暴露出来） */
    fun progressNotification(ctx: Context, text: String): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("正在出图…")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)                       // 不能划掉
            .setOnlyAlertOnce(true)                 // 文案更新不重复响
            .setShowWhen(false)
            .setProgress(0, 0, true)                // 不确定进度条（要转圈）
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(ctx))
            .build()
    }

    fun updateProgress(ctx: Context, text: String) {
        currentText = text
        post(ctx, ID_PROGRESS, progressNotification(ctx, text))
    }

    /** 出图完成（会响一声；点开回到 App） */
    fun done(ctx: Context, info: String, warn: String = "") {
        val body = if (warn.isBlank()) "已保存到「历史」页 · " + info else warn
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("锐评图已生成")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(ctx))
            .build()
        post(ctx, ID_DONE, n)
    }

    /** 出图失败（同样会响一声，省得用户白等） */
    fun fail(ctx: Context, msg: String) {
        val body = msg.ifBlank { "出图失败，详情看 App 内提示" }
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("出图失败")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(ctx))
            .build()
        post(ctx, ID_DONE, n)
    }

    /** 移除进度通知（前台服务停掉时也会自动清，这里兜一手） */
    fun cancelProgress(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(ID_PROGRESS) }
    }

    /** 移除"完成"通知：界面可见时改用弹窗，留着通知只会重复打扰 */
    fun cancelDone(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(ID_DONE) }
    }

    // ---------------------------------------------------------------- 内部

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            // App 已在栈里就复用那个实例，别叠出第二个 Activity
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 注意：Android 13+ 没有 POST_NOTIFICATIONS 权限时 notify 会静默失败（不抛异常），
     * 所以这里 runCatching + 日志，任何情况都不该让出图流程崩掉。
     */
    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, n: Notification) {
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
            .onFailure { Log.w("B50Notify", "通知发送失败：" + it.message) }
    }
}

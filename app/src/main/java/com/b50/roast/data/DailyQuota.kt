package com.b50.roast.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 本机每日「在线刷新水鱼数据」次数限制（默认 3 次/天，每台设备各自计数）。
 *
 * 为什么要限这个：每次刷新都要拿水鱼 client_id 去请求 /player/records，
 * 服务端对每用户有 200 次/日的配额，App 分发出去得自己兜一层。
 *
 * 规则：
 *   · **只在真正发起在线请求时 consume()**；用本地缓存出图不消耗任何次数
 *   · 首次拉取（本地从来没有缓存）免费，不扣次数
 *   · 跨自然日自动重置
 *   · 防小动作：记录上次成功时间，系统时间被往回拨（now < lastTs - 1min）一律判定超额
 *   · 这是本机限制，清 App 数据可以绕过 —— 分发场景够用，不当作防破解
 *
 * 注意：本类内部读写 SharedPreferences，**不是** Compose State。
 * UI 想跟着变，请读 AppState 里的 quotaLeft / quotaBlock 镜像值。
 */
class DailyQuota(ctx: Context, private val limit: Int = 3) {

    private val prefs = ctx.getSharedPreferences("b50_quota", Context.MODE_PRIVATE)
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    private fun today(): String = fmt.format(Date())

    private fun rollIfNeeded() {
        if (prefs.getString("day", null) != today()) {
            prefs.edit().putString("day", today()).putInt("used", 0).apply()
        }
    }

    fun limit(): Int = limit

    fun used(): Int {
        rollIfNeeded()
        return prefs.getInt("used", 0)
    }

    fun remaining(): Int = (limit - used()).coerceAtLeast(0)

    fun canGenerate(): Boolean = remaining() > 0 && !timeWentBack()

    /** 出图成功后调用，返回剩余次数 */
    fun consume(): Int {
        rollIfNeeded()
        val u = prefs.getInt("used", 0) + 1
        prefs.edit().putInt("used", u).putLong("lastTs", System.currentTimeMillis()).apply()
        return (limit - u).coerceAtLeast(0)
    }

    /**
     * 被拦下的原因。**空串 = 可以出图**。
     * UI 直接拿它当提示文案，省得在界面层再判一次逻辑。
     */
    fun blockReason(): String {
        rollIfNeeded()
        if (timeWentBack()) return "检测到系统时间被回拨，已暂停在线刷新"
        if (limit - prefs.getInt("used", 0) <= 0) {
            return "今日 " + limit + " 次在线刷新已用完，" + untilResetText()
        }
        return ""
    }

    /** "4 小时 12 分后重置" / "37 分钟后重置" */
    fun untilResetText(): String {
        val ms = resetAtMillis() - System.currentTimeMillis()
        if (ms <= 0) return "即将重置"
        val h = ms / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        return if (h > 0) "$h 小时 $m 分后重置" else "$m 分钟后重置"
    }

    /** 次日 0 点（UI 倒计时用） */
    fun resetAtMillis(): Long {
        val c = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    private fun timeWentBack(): Boolean {
        val last = prefs.getLong("lastTs", 0L)
        return last > 0 && System.currentTimeMillis() < last - 60_000L
    }
}

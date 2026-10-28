package com.b50.roast.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.b50.roast.data.RoastNotify

/**
 * 出图期间的"保活前台服务"。
 *
 * **它不干业务**，只做一件事：把进程提到前台优先级，别让系统/厂商省电把出图的协程冻住。
 * 实测（本机 MIUI，2026-10-28）：没有前台服务时，用户点完出图切到微信，b50 立刻
 * `visible->invisible(48883ms) visible->background`，日志两分钟不动 —— 回来才继续跑。
 *
 * 为什么业务仍留在 Activity 的协程里：出图链路（Python 两次进程 + LLM）依赖 `AppState`
 * 里一大堆 Compose 状态，搬到 Service 里要重构一大片，收益不抵风险。
 * 前台服务只需保证"进程不被回收、不被冻结"，协程就能跑完。
 *
 * Android 14+ 强制声明 `foregroundServiceType`，这里用 `dataSync`
 * （语义就是"短时间的数据同步/生成"，与 manifest 里的
 * `FOREGROUND_SERVICE_DATA_SYNC` 权限成对出现，两者缺一不可）。
 */
class RenderKeepAlive : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 5 秒内把通知挂上去，否则系统会 ANR/抛 RemoteServiceException
        val n = RoastNotify.progressNotification(this, RoastNotify.currentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                RoastNotify.ID_PROGRESS, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(RoastNotify.ID_PROGRESS, n)
        }
        Log.w("B50Notify", "前台服务已启动（出图保活）")
        return START_NOT_STICKY      // 被系统杀掉不用自动重建：出图状态在进程里，重建也没意义
    }

    override fun onDestroy() {
        Log.w("B50Notify", "前台服务已停止")
        super.onDestroy()
    }

    companion object {
        /** 出图开始前调用。失败（比如系统限制）不影响主流程，只是少了保活。 */
        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RenderKeepAlive::class.java))
            }.onFailure { Log.w("B50Notify", "保活服务启动失败：" + it.message) }
        }

        /** 出图结束后调用（成功/失败/取消都要调）。 */
        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, RenderKeepAlive::class.java)) }
        }
    }
}

package com.lu.noir

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 1.0.6：扫描不再绑 Activity 的命。
 *
 * 之前扫描跑在 MainActivity 自己的 CoroutineScope(SupervisorJob()) 里，
 * onDestroy() 一调 scope.cancel()，扫描就被取消——切后台、锁屏后 vivo 回收
 * Activity、转屏，都会让一场扫了几分钟的扫描在快结束时白跑，界面直接没了。
 * scan-error.txt 里 2026-11-07 01:12:20 那条 Job was cancelled 就是这么来的：
 * 不是内存爆了（crash.txt 是空的），是 Activity 被系统销毁、扫描被连带取消。
 *
 * 改法：扫描跑在应用级单例 scope 里，Activity 销毁、重建都不影响它；
 * Activity 在 onCreate 里接回进度与结果；只有真正的扫描错误才写 scan-error.txt，
 * 用户主动点停不算错误。
 */
object ScanManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var scanJob: Job? = null
        private set
    var lastResult: ScanResult? = null
        private set
    var lastError: Throwable? = null
        private set
    var lastDone: Int = 0
        private set
    var lastTotal: Int = 0
        private set
    var lastPkg: String = ""
        private set

    val isRunning: Boolean get() = scanJob?.isActive == true

    /** 供 MainActivity 就地改列表后同步回来（卸载/停用/恢复后），保持重建后取到的还是最新列表。 */
    fun setLastResult(result: ScanResult?) {
        lastResult = result
    }

    // 由 Activity 在 onCreate 登记、onDestroy 注销；扫描本身不依赖它们，
    // 扫完的结果存在 lastResult 里，Activity 重建后自己取。
    var onProgress: ((done: Int, total: Int, pkg: String) -> Unit)? = null
    var onFinished: ((result: ScanResult?, error: Throwable?) -> Unit)? = null

    /** 开始扫描；已在扫时再点一次 = 用户主动停止。 */
    fun toggleScan(context: Context) {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            return
        }
        val appContext = context.applicationContext
        lastResult = null
        lastError = null
        lastDone = 0
        lastTotal = 0
        lastPkg = ""
        scanJob = scope.launch {
            try {
                val result = Scanner.scan(appContext) { done, total, pkg ->
                    lastDone = done
                    lastTotal = total
                    lastPkg = pkg
                    onProgress?.invoke(done, total, pkg)
                }
                lastResult = result
                onFinished?.invoke(result, null)
            } catch (e: CancellationException) {
                // 只有用户主动点停会走到这里，不是错误，不记日志。
                onFinished?.invoke(null, e)
            } catch (e: Throwable) {
                lastError = e
                logScanError(appContext, e)
                onFinished?.invoke(null, e)
            }
        }
    }

    private fun logScanError(context: Context, e: Throwable) {
        try {
            val dir = ApplyEngine.outDir(context)
            val f = java.io.File(dir, "scan-error.txt")
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            f.appendText("=== ${java.util.Date()} $e\n$sw\n")
        } catch (_: Throwable) {
        }
    }
}

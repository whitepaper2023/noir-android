package com.lu.noir

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import androidx.annotation.Keep
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 在 Shizuku 服务进程里以 shell (uid 2000) / root (uid 0) 身份运行。
 * 写法按 Shizuku-API 官方 demo：类本身就是 AIDL Stub，
 * destroy() 的 transaction code 必须是 16777114。
 */
class NoirShellService : IShellService.Stub {

    @Keep
    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun destroy() {
        System.exit(0)
    }

    override fun runCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val tOut = Thread { process.inputStream.copyTo(out) }
            val tErr = Thread { process.errorStream.copyTo(err) }
            tOut.start()
            tErr.start()
            val finished = process.waitFor()
            tOut.join(5000)
            tErr.join(5000)
            "exit=$finished\n" + out.toString(Charsets.UTF_8) +
                "\n---STDERR---\n" + err.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            "exit=-1\n\n---STDERR---\n" + (e.message ?: e.toString())
        }
    }

    override fun whoami(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "id"))
            "exit=" + process.waitFor() + "\n" +
                process.inputStream.readBytes().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            "exit=-1\n" + (e.message ?: e.toString())
        }
    }
}

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    companion object {
        fun parse(raw: String): ShellResult {
            val firstLine = raw.lineSequence().firstOrNull().orEmpty()
            val code = firstLine.removePrefix("exit=").trim().toIntOrNull() ?: -1
            val body = raw.substringAfter("\n", "")
            val parts = body.split("\n---STDERR---\n", limit = 2)
            return ShellResult(code, parts.getOrNull(0).orEmpty(), parts.getOrNull(1).orEmpty())
        }
    }
}

object ShizukuShell {

    const val REQUEST_CODE = 1001

    fun status(context: Context): String {
        return try {
            if (Shizuku.isPreV11()) return "Shizuku 版本过低（需 v11+）"
            if (!Shizuku.pingBinder()) {
                return "Shizuku 未运行：请先安装 Shizuku App，Android 11+ 用无线调试启动它，再回到这里。"
            }
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (!granted) return "Shizuku 已运行，但未授权给 NOIR：点“检查 Shizuku”授权。"
            "Shizuku 已就绪（uid=${Shizuku.getUid()}，2000=adb shell，0=root）"
        } catch (e: Exception) {
            "Shizuku 状态未知：${e.message}"
        }
    }

    fun ensurePermission(onRequest: () -> Unit): Boolean {
        return try {
            if (Shizuku.isPreV11()) return false
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
            if (Shizuku.shouldShowRequestPermissionRationale()) return false
            onRequest()
            false
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            // 交给调用方在界面上显示错误
        }
    }

    /**
     * 经 UserService 执行一条 shell 命令。失败返回 exit=-1 而不是抛异常，
     * 调用方按文本展示。
     *
     * 1.0.1 改动（真机实测：卸载命令等满 60 秒才报超时）：
     * 先预检 Shizuku 是否在跑、有没有授权，不行立刻返回，不进空等；
     * 绑定必须在主线程发起（与官方 demo 一致），只等 15 秒——回调
     * 不来就说明服务端已断（无线调试掉线），不再空等 60 秒；
     * 绑上之后命令本身仍给 60 秒。
     */
    suspend fun runCommand(context: Context, command: String): ShellResult {
        try {
            if (!Shizuku.pingBinder()) {
                return ShellResult(
                    -1, "",
                    "Shizuku 未运行：请先在 Shizuku App 里启动，再回 NOIR 点“检查 SHIZUKU”"
                )
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return ShellResult(
                    -1, "",
                    "NOIR 未获 Shizuku 授权：请回 NOIR 点“检查 SHIZUKU”授权"
                )
            }
        } catch (e: Exception) {
            return ShellResult(-1, "", "Shizuku 状态检查失败：${e.message}")
        }
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, NoirShellService::class.java.name)
        ).daemon(false).processNameSuffix("noir").version(1)
        // conn 必须是 non-null val：Shizuku.bind/unbindUserService 的
        // ServiceConnection 参数是 @NonNull，nullable 变量传进去编不过。
        // continuation 经 contRef 递过去，绑定调用本身在主线程发起。
        var contRef: CancellableContinuation<IBinder?>? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                contRef?.let { if (it.isActive) it.resume(b) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                contRef?.let { if (it.isActive) it.resume(null) }
            }
        }
        val binder = withTimeoutOrNull(15_000L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    contRef = cont
                    try {
                        Shizuku.bindUserService(args, conn)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                    cont.invokeOnCancellation {
                        contRef = null
                        try {
                            Shizuku.unbindUserService(args, conn, true)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
        if (binder == null || !binder.pingBinder()) {
            try {
                Shizuku.unbindUserService(args, conn, true)
            } catch (_: Exception) {
            }
            return ShellResult(
                -1, "",
                "绑定 Shizuku 服务超时：服务端可能已断开（无线调试掉线是已知问题），" +
                    "请回 Shizuku App 点启动，再回 NOIR 点“检查 SHIZUKU”"
            )
        }
        return try {
            val service = IShellService.Stub.asInterface(binder)
            val raw = withContext(Dispatchers.IO) {
                withTimeoutOrNull(60_000L) {
                    try {
                        service.runCommand(command)
                    } catch (e: RemoteException) {
                        "exit=-1\n\n---STDERR---\n" + (e.message ?: e.toString())
                    }
                }
            }
            ShellResult.parse(raw ?: "exit=-1\n\n---STDERR---\ntimeout after 60s")
        } finally {
            try {
                Shizuku.unbindUserService(args, conn, true)
            } catch (_: Exception) {
            }
        }
    }
}

package com.lu.noir

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import androidx.annotation.Keep
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
     * 经 UserService 执行一条 shell 命令。每次调用都重新绑定，
     * 用完即解绑；失败返回 exit=-1 而不是抛异常，调用方按文本展示。
     */
    suspend fun runCommand(context: Context, command: String): ShellResult =
        withContext(Dispatchers.IO) {
            val raw = withTimeoutOrNull(60_000L) {
                suspendCancellableCoroutine { cont ->
                    val args = Shizuku.UserServiceArgs(
                        ComponentName(context.packageName, NoirShellService::class.java.name)
                    ).daemon(false).processNameSuffix("noir").version(1)
                    var service: IShellService? = null
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            try {
                                service = if (binder != null && binder.pingBinder()) {
                                    IShellService.Stub.asInterface(binder)
                                } else {
                                    null
                                }
                                val result = try {
                                    service?.runCommand(command) ?: "exit=-1\n\n---STDERR---\nno binder"
                                } catch (e: RemoteException) {
                                    "exit=-1\n\n---STDERR---\n" + (e.message ?: e.toString())
                                }
                                if (cont.isActive) cont.resume(result)
                            } finally {
                                try {
                                    Shizuku.unbindUserService(args, this, true)
                                } catch (e: Exception) {
                                    // 解绑失败不影响结果
                                }
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            if (cont.isActive) {
                                cont.resume("exit=-1\n\n---STDERR---\nservice disconnected")
                            }
                        }
                    }
                    try {
                        Shizuku.bindUserService(args, conn)
                    } catch (e: Exception) {
                        if (cont.isActive) {
                            cont.resume("exit=-1\n\n---STDERR---\n" + (e.message ?: e.toString()))
                        }
                    }
                    cont.invokeOnCancellation {
                        try {
                            Shizuku.unbindUserService(args, conn, true)
                        } catch (e: Exception) {
                        }
                    }
                }
            }
            ShellResult.parse(raw ?: "exit=-1\n\n---STDERR---\ntimeout after 60s")
        }
}

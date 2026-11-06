package com.lu.noir

import android.content.Context
import app.noir.core.ActionType
import app.noir.core.Recommendation
import java.io.File

data class AppliedAction(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

object ApplyEngine {

    // 绝不碰这些包：系统本身、NOIR 自己、Shizuku。
    private val PROTECTED = setOf("android", "com.lu.noir", "moe.shizuku.manager")

    fun outDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "noir-out")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 上游 NOIR 的 appops 命令：cmd appops set --user 0 <pkg> <op> ignore */
    fun commandFor(rec: Recommendation): String? {
        if (rec.pkg in PROTECTED) return null
        return when (rec.action) {
            ActionType.APPOP_PROJECT_MEDIA ->
                "cmd appops set --user 0 ${rec.pkg} PROJECT_MEDIA ignore"
            ActionType.APPOP_USAGE_STATS ->
                "cmd appops set --user 0 ${rec.pkg} GET_USAGE_STATS ignore"
            else -> null
        }
    }

    fun opFor(rec: Recommendation): String? = when (rec.action) {
        ActionType.APPOP_PROJECT_MEDIA -> "PROJECT_MEDIA"
        ActionType.APPOP_USAGE_STATS -> "GET_USAGE_STATS"
        else -> null
    }

    /**
     * 执行安全处置。执行前先逐条查当前模式并写入 rollback.txt，
     * 成功执行的动作才进 applied.txt；失败的也如实记录 stderr。
     */
    suspend fun applySafe(
        context: Context,
        recommendations: List<Recommendation>
    ): Pair<List<AppliedAction>, File> {
        val dir = outDir(context)
        val rollback = File(dir, "rollback.txt")
        val applied = File(dir, "applied.txt")
        val rollbackLines = ArrayList<String>()
        val results = ArrayList<AppliedAction>()
        val planned = recommendations
            .filter { it.autoSafe }
            .mapNotNull { rec ->
                val cmd = commandFor(rec) ?: return@mapNotNull null
                val op = opFor(rec) ?: return@mapNotNull null
                rec to (cmd to op)
            }

        // 先查原模式，写回滚
        for ((rec, pair) in planned) {
            val (cmd, op) = pair
            val get = ShizukuShell.runCommand(
                context,
                "cmd appops get --user 0 ${rec.pkg} $op"
            )
            val prev = parseAppOpsMode(get.stdout)
            rollbackLines.add(
                "# ${rec.pkg} $op was: $prev (get exit=${get.exitCode})"
            )
            rollbackLines.add(
                "cmd appops set --user 0 ${rec.pkg} $op $prev"
            )
            rollbackLines.add(
                "# apply: $cmd"
            )
        }
        rollback.writeText(
            if (rollbackLines.isEmpty()) "# no appops changes were applied\n"
            else rollbackLines.joinToString("\n") + "\n"
        )

        val appliedLines = ArrayList<String>()
        for ((rec, pair) in planned) {
            val (cmd, _) = pair
            val result = ShizukuShell.runCommand(context, cmd)
            results.add(AppliedAction(cmd, result.exitCode, result.stdout, result.stderr))
            appliedLines.add(
                "$cmd\t${if (result.exitCode == 0) "ok" else "fail"} (${result.exitCode})\n" +
                    "stdout: ${result.stdout}\nstderr: ${result.stderr}\n"
            )
        }
        applied.writeText(
            if (appliedLines.isEmpty()) "# no appops changes were applied\n"
            else appliedLines.joinToString("\n")
        )
        return results to rollback
    }

    /** 卸载单个应用：只在用户逐个确认后调用，记录恢复命令。 */
    suspend fun uninstallOne(context: Context, pkg: String): AppliedAction {
        require(pkg !in PROTECTED) { "受保护的包不允许卸载：$pkg" }
        val dir = outDir(context)
        val rollback = File(dir, "rollback.txt")
        val cmd = "pm uninstall --user 0 $pkg"
        val result = ShizukuShell.runCommand(context, cmd)
        val action = AppliedAction(cmd, result.exitCode, result.stdout, result.stderr)
        val existing = if (rollback.exists()) rollback.readText() else ""
        rollback.writeText(
            existing +
                "# uninstall $pkg (exit=${result.exitCode})\n" +
                "pm install-existing --user 0 $pkg\n"
        )
        return action
    }

    /** 停用单个应用：pm disable-user，恢复命令是 pm enable，写进 rollback.txt。 */
    suspend fun disableOne(context: Context, pkg: String): AppliedAction {
        require(pkg !in PROTECTED) { "受保护的包不允许停用：$pkg" }
        val dir = outDir(context)
        val rollback = File(dir, "rollback.txt")
        val cmd = "pm disable-user --user 0 $pkg"
        val result = ShizukuShell.runCommand(context, cmd)
        val action = AppliedAction(cmd, result.exitCode, result.stdout, result.stderr)
        val existing = if (rollback.exists()) rollback.readText() else ""
        rollback.writeText(
            existing +
                "# disable $pkg (exit=${result.exitCode})\n" +
                "pm enable --user 0 $pkg\n"
        )
        return action
    }

    /** 重新启用单个应用。 */
    suspend fun enableOne(context: Context, pkg: String): AppliedAction {
        require(pkg !in PROTECTED) { "受保护的包不允许启用：$pkg" }
        val cmd = "pm enable --user 0 $pkg"
        val result = ShizukuShell.runCommand(context, cmd)
        return AppliedAction(cmd, result.exitCode, result.stdout, result.stderr)
    }

    private fun parseAppOpsMode(stdout: String): String {
        // `cmd appops get` 输出形如 "PROJECT_MEDIA: ignore" / "PROJECT_MEDIA: default"
        val afterColon = stdout.substringAfter(":", "").trim()
        val first = afterColon.split(Regex("\\s+")).firstOrNull().orEmpty()
        return when (first) {
            "ignore", "deny", "allow", "default", "foreground" -> first
            else -> "default"
        }
    }
}

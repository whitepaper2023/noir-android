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

    // 1.0.7：处置过的 (pkg, op) 记在 prefs 里，格式 "pkg|OP"。
    // 扫描时读它来打“已处置”标、扣分、Advisor 跳过重复建议；恢复时清掉对应条。
    // prefs 才是 durable 的记录——applied.txt 每次处置都会被重写（旧行为），
    // 不能靠它当唯一记录。
    private const val PREFS = "noir"
    private const val KEY_APPLIED_OPS = "applied_ops"
    private const val KEY_APPLIED_MIGRATED = "applied_ops_migrated"

    /** pkg -> 已处置的 op 集合。第一次调用时把 1.0.7 之前写进 applied.txt 的记录并进来。 */
    fun appliedOps(context: Context): Map<String, Set<String>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_APPLIED_MIGRATED, false)) {
            // 合并而不是覆盖：recordApplied 可能已经先写了新条目，不能丢。
            val existing = prefs.getStringSet(KEY_APPLIED_OPS, emptySet()).orEmpty()
            val merged = existing + migrateAppliedFromFile(context)
            prefs.edit()
                .putStringSet(KEY_APPLIED_OPS, merged)
                .putBoolean(KEY_APPLIED_MIGRATED, true)
                .apply()
        }
        val raw = prefs.getStringSet(KEY_APPLIED_OPS, emptySet()).orEmpty()
        val out = HashMap<String, MutableSet<String>>()
        raw.forEach { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) out.getOrPut(parts[0]) { mutableSetOf() }.add(parts[1])
        }
        return out
    }

    fun recordApplied(context: Context, pkg: String, op: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_APPLIED_OPS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (set.add("$pkg|$op")) prefs.edit().putStringSet(KEY_APPLIED_OPS, set).apply()
    }

    fun clearApplied(context: Context, pkg: String, op: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_APPLIED_OPS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (set.remove("$pkg|$op")) prefs.edit().putStringSet(KEY_APPLIED_OPS, set).apply()
    }

    /**
     * 1.0.7 迁移：1.0.7 之前处置过的、只留在 applied.txt 里的记录，从文件里
     * 补回 prefs。applied.txt 每行形如
     * `cmd appops set --user 0 <pkg> <op> ignore\tok (0)`，只认 ok 的行。
     */
    private fun migrateAppliedFromFile(context: Context): Set<String> {
        return try {
            val file = File(outDir(context), "applied.txt")
            if (!file.exists()) return emptySet()
            Regex("""cmd appops set --user 0 (\S+) (\S+) ignore\s+ok \(0\)""")
                .findAll(file.readText())
                .map { "${it.groupValues[1]}|${it.groupValues[2]}" }
                .toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

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
     * 执行安全处置。先逐条查当前模式记在内存里，执行完只把成功的写进
     * rollback.txt（追加，不覆盖已有回滚行；同一 (pkg, op) 只保留最早
     * 那条）。1.0.8（同步上游 0.4.2）：以前是执行前就写回滚，命令失败了
     * 也会留一条没执行过的回滚行；现在失败的不写，恢复时不再出现没
     * 动过的操作。成功执行的动作才进 applied.txt；失败的也如实记录 stderr。
     */
    suspend fun applySafe(
        context: Context,
        recommendations: List<Recommendation>
    ): Pair<List<AppliedAction>, File> {
        val dir = outDir(context)
        val rollback = File(dir, "rollback.txt")
        val applied = File(dir, "applied.txt")
        // 1.0.4：记下文件里已有的 (pkg, op) 回滚行。最早那条恢复的是最初状态；
        // 同一处置跑第二遍时查到的“原模式”已是第一遍改完的状态，再写一条反而会把
        // 恢复目标写错（恢复完还停在 ignore），所以已有的不再重复写。
        val coveredOps = if (rollback.exists()) {
            Regex("""cmd appops set --user 0 (\S+) (\S+)""")
                .findAll(rollback.readText())
                .map { it.groupValues[1] to it.groupValues[2] }
                .toSet()
        } else {
            emptySet()
        }
        val rollbackLines = ArrayList<String>()
        val results = ArrayList<AppliedAction>()
        val planned = recommendations
            .filter { it.autoSafe }
            .mapNotNull { rec ->
                val cmd = commandFor(rec) ?: return@mapNotNull null
                val op = opFor(rec) ?: return@mapNotNull null
                rec to (cmd to op)
            }

        // 先查原模式，记在内存里，不写文件——只把执行成功的回滚行写进去
        //（1.0.8，见函数注释）。同一 (pkg, op) 只保留最早那条：最早那条
        // 恢复的是最初状态，第二遍查到的“原模式”已是第一遍改完的状态，
        // 再写一条反而会把恢复目标写错。
        val pendingRollback = HashMap<Pair<String, String>, List<String>>()
        for ((rec, pair) in planned) {
            val (cmd, op) = pair
            val get = ShizukuShell.runCommand(
                context,
                "cmd appops get --user 0 ${rec.pkg} $op"
            )
            val prev = parseAppOpsMode(get.stdout)
            if ((rec.pkg to op) !in coveredOps) {
                pendingRollback[rec.pkg to op] = listOf(
                    "# ${rec.pkg} $op was: $prev (get exit=${get.exitCode})",
                    "cmd appops set --user 0 ${rec.pkg} $op $prev",
                    "# apply: $cmd"
                )
            }
        }

        val appliedLines = ArrayList<String>()
        for ((rec, pair) in planned) {
            val (cmd, op) = pair
            val result = ShizukuShell.runCommand(context, cmd)
            results.add(AppliedAction(cmd, result.exitCode, result.stdout, result.stderr))
            // 1.0.7：成功的才记进 prefs——扫描靠它打“已处置”标、扣分、
            // 跳过重复建议；失败的不记，重扫还会继续建议。
            // 1.0.8：成功的回滚行也在这里才进 rollback.txt（见函数注释）。
            if (result.exitCode == 0) {
                recordApplied(context, rec.pkg, op)
                pendingRollback[rec.pkg to op]?.let { rollbackLines.addAll(it) }
            }
            appliedLines.add(
                "$cmd\t${if (result.exitCode == 0) "ok" else "fail"} (${result.exitCode})\n" +
                    "stdout: ${result.stdout}\nstderr: ${result.stderr}\n"
            )
        }
        // 1.0.4：rollback.txt 改追加，不再覆盖。卸载/停用本来就是追加的，
        // 只有这里是覆盖——跑一次安全处置就会把之前卸载/停用的恢复命令冲掉。
        if (rollbackLines.isNotEmpty()) {
            val existing = if (rollback.exists()) rollback.readText() else ""
            rollback.writeText(
                existing +
                    (if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n") +
                    rollbackLines.joinToString("\n") + "\n"
            )
        } else if (!rollback.exists()) {
            rollback.writeText("# no appops changes were applied\n")
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

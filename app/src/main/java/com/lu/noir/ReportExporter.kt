package com.lu.noir

import android.content.Context
import android.os.Build
import app.noir.core.Device
import app.noir.core.Report
import app.noir.core.ReportMeta
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportExporter {

    /**
     * 写到应用专属外存目录（无需存储权限）：
     * <external-files>/noir-out/report.json|md|html, domains-*.txt, plan.txt
     * 返回目录，界面直接把路径告诉用户，用文件管理器 / USB 取走。
     */
    fun export(context: Context, result: ScanResult): File {
        val dir = ApplyEngine.outDir(context)
        val device = Device(
            serial = "this-device",
            state = "device",
            model = Build.MODEL,
            product = Build.PRODUCT
        )
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
        val meta = ReportMeta(
            tool = "NOIR Android 1.0.0",
            host = "Android",
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT.toString(),
            build = Build.FINGERPRINT,
            generatedAt = now
        )
        val entries = result.apps.map { it.entry }
        File(dir, "report.json").writeText(
            Report.json(device, entries, result.recommendations, meta)
        )
        File(dir, "report.md").writeText(
            Report.markdown(device, entries, result.recommendations, meta)
        )
        File(dir, "report.html").writeText(
            Report.html(device, entries, result.recommendations, meta)
        )
        File(dir, "domains-trackers.txt").writeText(
            result.domains.trackers.joinToString("\n")
        )
        File(dir, "domains-other.txt").writeText(
            result.domains.review.joinToString("\n")
        )
        File(dir, "domains-functional.txt").writeText(
            result.domains.functional.joinToString("\n")
        )
        // domains.txt 兼容上游：只含 trackers
        File(dir, "domains.txt").writeText(
            result.domains.trackers.joinToString("\n")
        )
        val plan = StringBuilder()
        for (rec in result.recommendations) {
            val cmd = ApplyEngine.commandFor(rec)
            if (cmd != null) {
                plan.append("# ${rec.pkg} ${rec.action.label} [${rec.risk}] ${rec.reason}\n")
                plan.append("adb shell $cmd\n")
            } else {
                plan.append("# ${rec.pkg} ${rec.action.label} [${rec.risk}] ${rec.reason}（需人工复核）\n")
            }
        }
        File(dir, "plan.txt").writeText(plan.toString())
        return dir
    }
}

package com.lu.noir

import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null
    private var lastResult: ScanResult? = null
    private var allApps: List<ScannedApp> = emptyList()
    private var shownApps: List<ScannedApp> = emptyList()
    private lateinit var adapter: AppAdapter
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView
    private lateinit var progressBar: ProgressBar

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val ok = grantResult == PackageManager.PERMISSION_GRANTED
            statusText.text = if (ok) {
                "Shizuku 已授权。\n" + ShizukuShell.status(this)
            } else {
                "Shizuku 授权被拒绝：处置功能不可用，只读扫描仍可用。"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        summaryText = findViewById(R.id.summaryText)
        progressBar = findViewById(R.id.progressBar)

        adapter = AppAdapter { app -> showDetail(app) }
        findViewById<RecyclerView>(R.id.appList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.scanButton).setOnClickListener { startScan() }
        findViewById<Button>(R.id.shizukuButton).setOnClickListener { checkShizuku() }
        findViewById<Button>(R.id.exportButton).setOnClickListener { exportReport() }
        findViewById<Button>(R.id.applyButton).setOnClickListener { confirmSafeApply() }
        findViewById<EditText>(R.id.searchBox).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })
        Shizuku.addRequestPermissionResultListener(permissionListener)
        statusText.text = "NOIR：本机隐私审计，不联网、不上传。\n" + ShizukuShell.status(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        scope.cancel()
    }

    private fun checkShizuku() {
        val granted = ShizukuShell.ensurePermission { ShizukuShell.requestPermission() }
        statusText.text = if (granted) {
            "Shizuku 已就绪。\n" + ShizukuShell.status(this)
        } else {
            "正在请求 Shizuku 授权（或 Shizuku 未运行）：\n" +
                "1. 安装 Shizuku App；2. Android 11+ 在开发者选项里开无线调试，用 Shizuku 配对启动；" +
                "3. 回到 NOIR 点“检查 Shizuku”授权。\n" + ShizukuShell.status(this)
        }
        if (!granted) {
            // ensurePermission 已在需要时发起请求；未运行时提示用户先启动 Shizuku
            val running = try {
                Shizuku.pingBinder()
            } catch (e: Exception) {
                false
            }
            if (!running) {
                Toast.makeText(this, "Shizuku 未运行，请先在 Shizuku App 里启动", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startScan() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            return
        }
        scanJob = scope.launch {
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            statusText.text = "正在扫描…"
            try {
                val result = Scanner.scan(this@MainActivity) { done, total, pkg ->
                    progressBar.max = total
                    progressBar.progress = done
                    statusText.text = "正在扫描 $done/$total：$pkg"
                }
                lastResult = result
                allApps = result.apps
                applyFilter("")
                val high = result.apps.count { it.entry.score >= 40 }
                val med = result.apps.count { it.entry.score in 25..39 }
                summaryText.text = "共 ${result.apps.size} 个应用，高风险 $high 个，中风险 $med 个，" +
                    "安全处置 ${result.recommendations.count { it.autoSafe }} 条，" +
                    "上报域名 ${result.domains.trackers.size} 个可拦截"
                statusText.text = "扫描完成。点应用看详情；没装 Shizuku 也能扫和导出，" +
                    "处置要 Shizuku。\n" + ShizukuShell.status(this@MainActivity)
            } catch (e: Exception) {
                statusText.text = "扫描失败：${e.message}"
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        shownApps = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.entry.pkg.lowercase().contains(q) || it.label.lowercase().contains(q)
            }
        }
        adapter.submit(shownApps)
    }

    private fun showDetail(app: ScannedApp) {
        val e = app.entry
        val sb = StringBuilder()
        sb.append("应用：${app.label}\n包名：${e.pkg}\n")
        sb.append("分数：${e.score}  分类：${e.category.label}  厂商：${e.vendor ?: "—"}\n")
        sb.append("系统应用：${if (e.isSystem) "是" else "否"}  可调试：${if (e.debuggable) "是" else "否"}\n")
        sb.append("UID：${e.uid}  共享 UID：${e.sharedUid ?: "—"}  安装来源：${e.installer ?: "—"}\n\n")
        sb.append("可读数据：${e.dataTypes.joinToString("、") { it.label }.ifEmpty { "无" }}\n\n")
        sb.append("权限（${e.permissions.size}）：\n${e.permissions.joinToString("\n")}\n\n")
        sb.append("上报端点（${e.uploadEndpoints.size}）：\n${e.uploadEndpoints.joinToString("\n")}\n\n")
        sb.append("全部端点（${e.endpoints.size}）：\n${e.endpoints.take(50).joinToString("\n")}\n\n")
        sb.append("数据可能流向：\n")
        if (e.leaks.isEmpty()) sb.append("无上报端点，暂无流向\n") else {
            e.leaks.forEach { (type, hosts) ->
                sb.append("${type.label} → ${hosts.joinToString("、")}\n")
            }
        }
        sb.append("\nSDK/追踪：${app.trackers.joinToString("、").ifEmpty { "无" }}\n")
        sb.append("DeepScan 信号：${app.deepHits.joinToString("、").ifEmpty { "无" }}\n")
        app.manifest?.let { m ->
            sb.append("\n清单：明文流量=${m.cleartext}  备份=${m.allowBackup}  " +
                "targetSdk=${m.targetSdk}  无权限导出组件=${m.exportedWithoutPermission.size} 个\n")
            m.exportedWithoutPermission.take(10).forEach {
                sb.append("  ${it.kind} ${it.name}（已导出、无权限保护）\n")
            }
        }
        val recs = lastResult?.recommendations?.filter { it.pkg == e.pkg }.orEmpty()
        if (recs.isNotEmpty()) {
            sb.append("\n处置建议：\n")
            recs.forEach {
                sb.append("· ${it.action.label} [${it.risk}] ${it.reason}（自动安全：${it.autoSafe}）\n")
            }
        }
        val textView = TextView(this).apply {
            text = sb.toString()
            setPadding(32, 24, 32, 24)
            textSize = 13f
        }
        val scroll = ScrollView(this).apply { addView(textView) }
        val builder = AlertDialog.Builder(this)
            .setTitle(app.label)
            .setView(scroll)
            .setPositiveButton("关闭", null)
        if (e.pkg !in setOf("android", "com.lu.noir", "moe.shizuku.manager")) {
            builder.setNeutralButton("卸载此应用（需 Shizuku）") { _, _ ->
                confirmUninstall(e.pkg)
            }
        }
        builder.show()
    }

    private fun confirmUninstall(pkg: String) {
        AlertDialog.Builder(this)
            .setTitle("卸载 $pkg？")
            .setMessage("将执行：pm uninstall --user 0 $pkg\n系统分区里的 APK 还在，恢复命令会写进 rollback.txt：\npm install-existing --user 0 $pkg")
            .setPositiveButton("卸载") { _, _ ->
                scope.launch {
                    val ok = ShizukuShell.ensurePermission { ShizukuShell.requestPermission() }
                    if (!ok) {
                        Toast.makeText(this@MainActivity, "需要 Shizuku 授权", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val result = withContext(Dispatchers.IO) {
                        ApplyEngine.uninstallOne(this@MainActivity, pkg)
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("卸载结果：exit=${result.exitCode}")
                        .setMessage("stdout：\n${result.stdout}\n\nstderr：\n${result.stderr}")
                        .setPositiveButton("好", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmSafeApply() {
        val result = lastResult
        if (result == null) {
            Toast.makeText(this, "请先扫描", Toast.LENGTH_SHORT).show()
            return
        }
        val safe = result.recommendations.filter { it.autoSafe }
        if (safe.isEmpty()) {
            Toast.makeText(this, "没有可安全自动执行的处置", Toast.LENGTH_SHORT).show()
            return
        }
        val lines = safe.mapNotNull { ApplyEngine.commandFor(it) }
        AlertDialog.Builder(this)
            .setTitle("执行 ${lines.size} 条安全处置？")
            .setMessage(
                "只执行 appops 级别、可回滚的动作，执行前先查原模式写 rollback.txt：\n\n" +
                    lines.joinToString("\n") +
                    "\n\n卸载不在其中，要逐个在详情里确认。"
            )
            .setPositiveButton("执行") { _, _ ->
                scope.launch {
                    val ok = ShizukuShell.ensurePermission { ShizukuShell.requestPermission() }
                    if (!ok) {
                        Toast.makeText(this@MainActivity, "需要 Shizuku 授权", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val (applied, rollback) = withContext(Dispatchers.IO) {
                        ApplyEngine.applySafe(this@MainActivity, safe)
                    }
                    val done = applied.count { it.exitCode == 0 }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("处置完成：$done/${applied.size} 成功")
                        .setMessage(
                            "回滚文件：${rollback.absolutePath}\n" +
                                "执行记录：${rollback.parent}/applied.txt\n\n" +
                                applied.joinToString("\n") {
                                    "${it.command} → exit=${it.exitCode}"
                                }
                        )
                        .setPositiveButton("好", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportReport() {
        val result = lastResult
        if (result == null) {
            Toast.makeText(this, "请先扫描", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch(Dispatchers.IO) {
            val dir = ReportExporter.export(this@MainActivity, result)
            withContext(Dispatchers.Main) {
                statusText.text = "报告已导出到：\n${dir.absolutePath}\n" +
                    "含 report.json / report.md / report.html、domains-*.txt、plan.txt"
                Toast.makeText(this@MainActivity, "已导出到 ${dir.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private class AppAdapter(private val onClick: (ScannedApp) -> Unit) :
        RecyclerView.Adapter<AppAdapter.Holder>() {

        private var items: List<ScannedApp> = emptyList()

        fun submit(list: List<ScannedApp>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.titleText)
            val meta: TextView = view.findViewById(R.id.metaText)
            val score: TextView = view.findViewById(R.id.scoreText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = items[position]
            val e = app.entry
            holder.title.text = "${app.label}（${e.pkg}）"
            holder.meta.text = "${e.category.label} · ${e.vendor ?: "—"} · " +
                "${if (e.isSystem) "系统" else "用户"} · 权限 ${e.permissions.size} · 上报 ${e.uploadEndpoints.size}"
            holder.score.text = "风险分：${e.score}" +
                if (e.score >= 40) "（高）" else if (e.score >= 25) "（中）" else "（低）"
            holder.itemView.setOnClickListener { onClick(app) }
        }

        override fun getItemCount(): Int = items.size
    }
}

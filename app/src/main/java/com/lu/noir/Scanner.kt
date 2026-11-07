package com.lu.noir

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import app.noir.core.Advisor
import app.noir.core.AppEntry
import app.noir.core.Classifier
import app.noir.core.Domains
import app.noir.core.Recommendation
import app.noir.core.Rules
import app.noir.core.apk.ApkScan
import app.noir.core.apk.ManifestAnalyzer
import app.noir.core.apk.ManifestFacts
import app.noir.core.apk.Trackers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

data class ScannedApp(
    val entry: AppEntry,
    val label: String,
    val manifest: ManifestFacts?,
    val trackers: Set<String>,
    val deepHits: List<String>,
    val apkPaths: List<String>,
    // 1.0.2：是否已被停用（pm disable-user）。扫描时按 ApplicationInfo.enabled 取，
    // 停用/启用操作后就地更新，不用重扫。
    val disabled: Boolean = false,
    // 1.0.3：是否系统受保护应用。只认实际碰到过系统拦截的包（见 MainActivity.markProtected），
    // 存在 prefs 里，扫描时读出来打标，不靠猜、不靠包名黑名单。
    val systemProtected: Boolean = false
)

data class ScanResult(
    val apps: List<ScannedApp>,
    val recommendations: List<Recommendation>,
    val domains: app.noir.core.DomainExport
)

object Scanner {

    private val DEEP_KEYWORDS = listOf(
        "Runtime.exec" to "Runtime.exec",
        "DexClassLoader" to "DexClassLoader",
        "setApplicationEnabledSetting" to "setApplicationEnabledSetting",
        "MediaProjection" to "MediaProjection",
        "SystemProperties" to "SystemProperties.set",
        "AccessibilityService" to "无障碍服务",
        "NotificationListenerService" to "通知监听",
        "DeviceAdminReceiver" to "设备管理员",
        "getDeviceId" to "设备标识",
        "getSubscriberId" to "SIM 标识"
    )

    suspend fun scan(
        context: Context,
        onProgress: (done: Int, total: Int, pkg: String) -> Unit
    ): ScanResult = withContext(Dispatchers.Default) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(
            PackageManager.GET_PERMISSIONS or
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS
        )
        val total = packages.size
        // 1.0.3：受保护标记从 prefs 读（见 MainActivity.markProtected）。
        val protectedPkgs = context.getSharedPreferences("noir", Context.MODE_PRIVATE)
            .getStringSet("protected_pkgs", emptySet()) ?: emptySet()
        // 1.0.7：已处置的 (pkg, op)——打标、扣分、Advisor 跳过重复建议的依据。
        val appliedMap = ApplyEngine.appliedOps(context)
        val out = ArrayList<ScannedApp>()
        packages.forEachIndexed { index, pkgInfo ->
            // 1.0.6：每个应用开头检查一次取消——用户点停能立刻停，不用等整轮跑完；
            // 阻塞式 dex 读本身不停顿，取消信号靠这里递进。
            currentCoroutineContext().ensureActive()
            val pkg = pkgInfo.packageName
            try {
                val appInfo = pkgInfo.applicationInfo ?: return@forEachIndexed
                val label = try {
                    appInfo.loadLabel(pm).toString()
                } catch (e: Exception) {
                    pkg
                }
                val perms = pkgInfo.requestedPermissions?.toList().orEmpty()
                    .map { it.removePrefix("android.permission.") }
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val apkPaths = ArrayList<String>()
                appInfo.sourceDir?.let { apkPaths.add(it) }
                appInfo.splitSourceDirs?.let { apkPaths.addAll(it) }

                // 端点抽取：只对声明 INTERNET 的应用扫 dex，和上游 NOIR 一致。
                val endpoints = LinkedHashSet<String>()
                if ("INTERNET" in perms) {
                    for (path in apkPaths) {
                        try {
                            endpoints.addAll(ApkScan.urls(File(path)))
                        } catch (e: Exception) {
                            // 单个 APK 读不到不中断整轮扫描
                        }
                        if (endpoints.size >= 300) break
                    }
                }
                val manifest = try {
                    appInfo.sourceDir?.let { ManifestAnalyzer.analyze(File(it)) }
                } catch (e: Exception) {
                    null
                }
                val debuggable = manifest?.debuggable
                    ?: ((appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                // 1.0.7：处置过的能力不再计分——先算原始分，再扣被禁掉的那部分
                //（录屏 -5、使用统计 -1，见 Rules.remediatedScore），下限 0。
                val appliedOps = appliedMap[pkg].orEmpty()
                val score = Rules.remediatedScore(
                    Rules.score(perms, endpoints.toList(), isSystem, debuggable),
                    perms, appliedOps
                )
                val entry = AppEntry(
                    pkg = pkg,
                    uid = appInfo.uid.toString(),
                    isSystem = isSystem,
                    debuggable = debuggable,
                    installer = try {
                        pm.getInstallerPackageName(pkg)
                    } catch (e: Exception) {
                        null
                    },
                    permissions = perms,
                    endpoints = endpoints.toList(),
                    score = score,
                    category = Classifier.category(pkg, endpoints.toList(), perms, isSystem),
                    vendor = Classifier.vendor(pkg, endpoints.toList()),
                    sharedUid = pkgInfo.sharedUserId,
                    appliedOps = appliedOps
                )
                val deepHits = deepScan(apkPaths)
                out.add(
                    ScannedApp(
                        entry = entry,
                        label = label,
                        manifest = manifest,
                        trackers = Trackers.fromEndpoints(endpoints.toList()),
                        deepHits = deepHits,
                        apkPaths = apkPaths,
                        disabled = !appInfo.enabled,
                        systemProtected = pkg in protectedPkgs
                    )
                )
            } catch (e: Throwable) {
                // 单个应用失败不中断整轮扫描。1.0.4：改抓 Throwable——
                // OutOfMemoryError 这类 Error 不进 catch (Exception)，一个大 dex
                // 就能崩掉整轮扫描（旧版本也崩过）；现在单个应用出任何问题都只跳过它。
            }
            onProgress(index + 1, total, pkg)
        }
        val sorted = out.sortedByDescending { it.entry.score }
        val entries = sorted.map { it.entry }
        ScanResult(
            apps = sorted,
            recommendations = Advisor.advise(entries),
            domains = Domains.export(entries)
        )
    }

    private fun deepScan(apkPaths: List<String>): List<String> {
        val hits = LinkedHashSet<String>()
        for (path in apkPaths) {
            try {
                ZipFile(File(path)).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (!e.name.startsWith("classes") || !e.name.endsWith(".dex")) continue
                        if (e.size <= 0 || e.size > 64L * 1024 * 1024) continue
                        // 1.0.4：流式读 dex，不再 readBytes() 整文件 + 转 String 复制一份。
                        // 一个大 dex 两份几十 MB 的拷贝是闪退的头号嫌疑（OutOfMemoryError，
                        // Error 不进 catch (Exception)，直接崩掉整轮扫描）。
                        // 每块 64KB，块之间留 64 字节重叠（最长的关键字 27 字符），
                        // 跨块的关键字也能命中，内存峰值 64KB 出头。
                        zip.getInputStream(e).use { input ->
                            val buf = ByteArray(64 * 1024)
                            var tail = ByteArray(0)
                            while (hits.size < DEEP_KEYWORDS.size) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                val window = tail + buf.copyOf(n)
                                // dex 里的字符串是 ASCII/UTF-8，用 Latin-1 解码可直接搜关键字。
                                val text = String(window, Charsets.ISO_8859_1)
                                for ((keyword, label) in DEEP_KEYWORDS) {
                                    if (text.contains(keyword)) hits.add(label)
                                }
                                tail = if (window.size > 64) {
                                    window.copyOfRange(window.size - 64, window.size)
                                } else {
                                    window
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // 读不到就跳过，不报错
            }
            if (hits.size >= DEEP_KEYWORDS.size) break
        }
        return hits.toList()
    }
}

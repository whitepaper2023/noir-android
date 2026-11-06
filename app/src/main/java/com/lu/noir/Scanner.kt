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
    val disabled: Boolean = false
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
        val out = ArrayList<ScannedApp>()
        packages.forEachIndexed { index, pkgInfo ->
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
                val score = Rules.score(perms, endpoints.toList(), isSystem, debuggable)
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
                    sharedUid = pkgInfo.sharedUserId
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
                        disabled = !appInfo.enabled
                    )
                )
            } catch (e: Exception) {
                // 单个应用失败不中断整轮扫描
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
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        // dex 里的字符串是 ASCII/UTF-8，用 Latin-1 解码可直接搜关键字。
                        val text = String(bytes, Charsets.ISO_8859_1)
                        for ((keyword, label) in DEEP_KEYWORDS) {
                            if (text.contains(keyword)) hits.add(label)
                        }
                    }
                }
            } catch (e: Exception) {
                // 读不到就跳过，不报错
            }
            if (hits.size >= DEEP_KEYWORDS.size) break
        }
        return hits.toList()
    }
}

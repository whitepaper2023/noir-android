/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

enum class ActionType(val label: String) {
    APPOP_PROJECT_MEDIA("禁止录屏"),
    APPOP_USAGE_STATS("禁止读取使用统计"),
    UNINSTALL_USER("对当前用户卸载"),
    REVIEW("人工复核"),
    BLOCK_DNS("DNS 拦截")
}

data class Recommendation(
    val pkg: String,
    val action: ActionType,
    val risk: String,
    val reason: String,
    val autoSafe: Boolean
)

/**
 * Turns findings into a small, ordered set of actions.
 *
 * Two deliberate restraints:
 *  - screen-capture is only flagged on system components that also ship upload
 *    endpoints, never on apps where screen sharing is a real feature;
 *  - NOIR never proposes uninstalling a user app. Those become REVIEW, because
 *    "WeChat uploads a lot" is not a reason to uninstall WeChat.
 */
object Advisor {

    private val CORE = setOf(
        "com.miui.securitycenter",
        "com.miui.guardprovider",
        "com.xiaomi.xmsf",
        "com.xiaomi.digitalkey",
        "com.android.systemui",
        "com.miui.home",
        "com.android.settings",
        "com.android.phone",
        "com.android.mms",
        "com.android.incallui"
    )

    /** Apps where screen capture is a legitimate, user-facing feature. */
    private val MEDIA_KEEP = setOf(
        "com.miui.screenrecorder",
        "com.milink.service",
        "com.miui.carlink",
        "com.miui.accessibility",
        "com.xiaomi.mirror",
        "com.android.chrome",
        "com.google.android.youtube",
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.eg.android.AlipayGphone",
        "tv.danmaku.bili",
        "com.verlintas.baic2",
        "com.verlintas.baic2.devtest",
        "bin.mt.plus"
    )

    fun advise(apps: List<AppEntry>): List<Recommendation> {
        val out = ArrayList<Recommendation>()

        apps.forEach { app ->
            if (app.pkg == "android") return@forEach

            val mediaWorthFlagging =
                "FOREGROUND_SERVICE_MEDIA_PROJECTION" in app.permissions &&
                    app.pkg !in MEDIA_KEEP &&
                    app.isSystem &&
                    (
                        app.category in setOf(Category.SECURITY, Category.AI_VOICE, Category.TELEMETRY) ||
                            app.uploadEndpoints.isNotEmpty()
                        )

            if (mediaWorthFlagging) {
                out += Recommendation(
                    app.pkg,
                    ActionType.APPOP_PROJECT_MEDIA,
                    "MED",
                    "系统组件，声明了录屏能力且存在上报端点",
                    autoSafe = true
                )
            }

            if ("PACKAGE_USAGE_STATS" in app.permissions &&
                app.category == Category.TELEMETRY &&
                app.isSystem
            ) {
                out += Recommendation(
                    app.pkg,
                    ActionType.APPOP_USAGE_STATS,
                    "MED",
                    "遥测类系统应用可读取应用使用统计",
                    autoSafe = true
                )
            }

            if ((app.category == Category.TELEMETRY || app.category == Category.ADS) && app.pkg !in CORE) {
                out += Recommendation(
                    app.pkg,
                    ActionType.REVIEW,
                    if (app.isSystem) "HIGH" else "MED",
                    if (app.isSystem) "遥测/广告类系统组件，无法卸载，建议 DNS 层拦截" else "遥测/广告密度高，评估后决定是否保留",
                    autoSafe = false
                )
            }

            if (app.uploadEndpoints.size >= 2 &&
                app.category in setOf(Category.TELEMETRY, Category.LOCATION, Category.ADS)
            ) {
                out += Recommendation(
                    app.pkg,
                    ActionType.BLOCK_DNS,
                    "LOW",
                    "存在多个上报端点，可在 DNS 层拦截",
                    autoSafe = false
                )
            }
        }

        return out
            .distinctBy { it.pkg to it.action }
            .sortedWith(compareBy({ riskRank(it.risk) }, { it.action.ordinal }))
    }

    private fun riskRank(risk: String): Int = when (risk) {
        "HIGH" -> 0
        "MED" -> 1
        else -> 2
    }
}

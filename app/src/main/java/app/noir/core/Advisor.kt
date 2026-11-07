/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

enum class ActionType(val label: String) {
    // 1.0.8（同步上游 0.4.2）：PROJECT_MEDIA 只撤销常驻授权，拦不住录屏，
    // 上游已把它从 advise() 的自动建议里删掉；枚举保留，旧的回滚/恢复
    // 和 1.0.7 已处置的记录还要认它。标签按上游改名，不再叫“禁止录屏”。
    APPOP_PROJECT_MEDIA("撤销常驻录屏授权"),
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
 * Deliberate restraints:
 *  - declaring screen-capture capability is not treated as an action: the
 *    PROJECT_MEDIA app-op only controls a permanent grant, not recording
 *    itself (1.0.8 同步上游 0.4.2，56246fda：删掉了录屏自动建议；
 *    之前“禁止录屏”的说法是错的，ignore 拦不住录屏）;
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

    fun advise(apps: List<AppEntry>): List<Recommendation> {
        val out = ArrayList<Recommendation>()

        apps.forEach { app ->
            if (app.pkg == "android") return@forEach

            // 1.0.8：录屏自动建议已删（见文件头注释）。GET_USAGE_STATS 的
            // “已处置跳过”保留（1.0.7 的机制，上游保留了这条建议）。
            if ("PACKAGE_USAGE_STATS" in app.permissions &&
                app.category == Category.TELEMETRY &&
                app.isSystem &&
                "GET_USAGE_STATS" !in app.appliedOps
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

            // 1.0.8（同步上游 0.4.2）：按不同上报 host 数，不按端点数——
            // 同一个 host 上两个端点只算 1 个。
            if (Exposure.uploadHosts(app).size >= 2 &&
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

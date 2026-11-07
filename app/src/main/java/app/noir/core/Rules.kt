/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

/**
 * The heuristics that turn raw permissions and URL strings into a risk signal.
 *
 * Everything here is deliberately simple and explainable: no ML, no opaque scoring.
 * A finding is always traceable to a permission or a string that exists in the APK.
 */
object Rules {

    val SENSITIVE: List<String> = listOf(
        "ACCESS_FINE_LOCATION",
        "ACCESS_COARSE_LOCATION",
        "ACCESS_BACKGROUND_LOCATION",
        "READ_CONTACTS",
        "READ_SMS",
        "RECEIVE_SMS",
        "READ_CALL_LOG",
        "READ_PHONE_STATE",
        "READ_PHONE_NUMBERS",
        "READ_PRIVILEGED_PHONE_STATE",
        "RECORD_AUDIO",
        "CAMERA",
        "BODY_SENSORS",
        "PACKAGE_USAGE_STATS",
        "QUERY_ALL_PACKAGES",
        "GET_ACCOUNTS",
        "READ_CALENDAR",
        "MANAGE_EXTERNAL_STORAGE",
        "READ_EXTERNAL_STORAGE",
        "READ_MEDIA_IMAGES",
        "READ_MEDIA_VIDEO",
        "READ_MEDIA_AUDIO",
        "SYSTEM_ALERT_WINDOW",
        "REQUEST_INSTALL_PACKAGES",
        "FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "CAPTURE_AUDIO_OUTPUT",
        "CAPTURE_VOICE_COMMUNICATION_OUTPUT",
        "WRITE_SECURE_SETTINGS"
    )

    // 1.0.8（同步上游 0.4.2）：上报路径词扩了（/stats、/logs、/tracking、
    // /analytics、/metrics、/crash 等以前漏了）；只看 URL 路径，查询参数
    // 里带个上报词不算上报，那是误报。
    private val UPLOAD = Regex(
        "/(report|reports|upload|uploads|collect|collection|track|tracking|tracker|" +
            "stat|stats|statistic|statistics|beacon|log|logs|logging|analytics|metrics|" +
            "crash|crashes)([/?]|$)",
        RegexOption.IGNORE_CASE
    )

    private val COMMAND = Regex(
        "/(command|commands|cmd|dispatch|execute|script)([/?]|$)",
        RegexOption.IGNORE_CASE
    )

    /** Only the path is evidence; query parameters often contain unrelated URLs. */
    private fun pathOf(url: String): String = url.substringBefore('?').substringBefore('#')

    fun isUpload(url: String): Boolean = UPLOAD.containsMatchIn(pathOf(url))

    fun isCommand(url: String): Boolean = COMMAND.containsMatchIn(pathOf(url))

    fun score(
        permissions: List<String>,
        endpoints: List<String>,
        isSystem: Boolean,
        debuggable: Boolean
    ): Int {
        var s = permissions.size
        if ("QUERY_ALL_PACKAGES" in permissions) s += 2
        if ("READ_PRIVILEGED_PHONE_STATE" in permissions) s += 3
        if ("FOREGROUND_SERVICE_MEDIA_PROJECTION" in permissions) s += 4
        if (isSystem) s += 2
        if (debuggable) s += 2
        if (endpoints.any { isCommand(it) }) s += 2
        if (endpoints.any { isUpload(it) }) s += 3
        return s
    }

    /**
     * 1.0.7：处置过的能力不再计分——只扣被禁掉的那部分，不虚构降幅。
     * GET_USAGE_STATS 被禁：扣 1（该权限在总数里只占 1），读使用统计
     * 是真的被禁掉了。
     * 1.0.8：PROJECT_MEDIA 的 -5 扣分删了——上游 0.4.2 指出，ignore 只
     * 撤销常驻授权，拦不住录屏，录屏能力还在，分数不该扣；连同“禁止录屏”
     * 的说法和自动建议一起拿掉（见 Advisor）。1.0.7 已记进 prefs 的
     * PROJECT_MEDIA 旧记录还显示“已处置”，只不再扣分。
     * 权限声明、端点、dex 调用都还在，所以大应用的分数不会一下掉出高风险，
     * “已处置”标识才是主要信号，扣分只是如实反映被禁掉的那块风险没了。
     * base 必须用 score() 刚算出的原始分重算，禁止在已扣过的分数上再扣。
     */
    fun remediatedScore(
        base: Int,
        permissions: List<String>,
        appliedOps: Set<String>
    ): Int {
        var s = base
        if ("GET_USAGE_STATS" in appliedOps &&
            "PACKAGE_USAGE_STATS" in permissions
        ) s -= 1
        return s.coerceAtLeast(0)
    }
}

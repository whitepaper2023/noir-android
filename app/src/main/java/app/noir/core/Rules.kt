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
        "ACCESS_BACKGROUND_LOCATION",
        "READ_CONTACTS",
        "READ_SMS",
        "RECEIVE_SMS",
        "READ_CALL_LOG",
        "READ_PHONE_STATE",
        "READ_PRIVILEGED_PHONE_STATE",
        "RECORD_AUDIO",
        "CAMERA",
        "PACKAGE_USAGE_STATS",
        "QUERY_ALL_PACKAGES",
        "GET_ACCOUNTS",
        "READ_CALENDAR",
        "MANAGE_EXTERNAL_STORAGE",
        "READ_MEDIA_IMAGES",
        "READ_MEDIA_VIDEO",
        "SYSTEM_ALERT_WINDOW",
        "REQUEST_INSTALL_PACKAGES",
        "FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "CAPTURE_AUDIO_OUTPUT",
        "CAPTURE_VOICE_COMMUNICATION_OUTPUT",
        "WRITE_SECURE_SETTINGS"
    )

    private val UPLOAD = Regex(
        "/(report|upload|collect|track|stat|beacon|log)([/?]|$)",
        RegexOption.IGNORE_CASE
    )

    private val COMMAND = Regex(
        "/(command|cmd|dispatch|execute|script)([/?]|$)",
        RegexOption.IGNORE_CASE
    )

    fun isUpload(url: String): Boolean = UPLOAD.containsMatchIn(url)

    fun isCommand(url: String): Boolean = COMMAND.containsMatchIn(url)

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
     * PROJECT_MEDIA 被禁：录屏能力没了，扣 5（权限本身 1 + 录屏加权 4）；
     * GET_USAGE_STATS 被禁：扣 1（该权限在总数里只占 1）。
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
        if ("PROJECT_MEDIA" in appliedOps &&
            "FOREGROUND_SERVICE_MEDIA_PROJECTION" in permissions
        ) s -= 5
        if ("GET_USAGE_STATS" in appliedOps &&
            "PACKAGE_USAGE_STATS" in permissions
        ) s -= 1
        return s.coerceAtLeast(0)
    }
}

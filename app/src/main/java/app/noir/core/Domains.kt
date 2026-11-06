/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

enum class DomainClass {
    /** Analytics / ads / crash reporting — safe to block for privacy. */
    TRACKER,
    /** Unknown purpose — the user should decide. */
    REVIEW,
    /** Push, login, updates, payments, store, cloud sync — blocking breaks features. */
    FUNCTIONAL
}

data class DomainExport(
    val trackers: List<String>,
    val review: List<String>,
    val functional: List<String>
)

/**
 * Splits the hosts an app actually talks to into "safe to block" and "do not
 * touch". A single unfiltered list is dangerous: importing it into Pi-hole or
 * AdGuard takes out push, Play Services, login and update endpoints.
 *
 * Classification is label-based, not substring soup: `status` must not be
 * mistaken for `stat`, and `schemas.android.com` is not a network endpoint.
 */
object Domains {

    private val IGNORED_HOSTS = setOf(
        "schemas.android.com",
        "www.w3.org",
        "w3.org",
        "xmlpull.org",
        "jakarta.apache.org",
        "apache.org",
        "ns.adobe.com",
        "xml.apache.org",
        "java.sun.com",
        "www.bea.com",
        "github.com",
        "githubusercontent.com",
        "codeberg.org",
        "schemas.microsoft.com",
        "developer.android.com",
        "issuetracker.google.com",
        "youtrack.jetbrains.com",
        "localhost"
    )

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private val IPV6 = Regex("^[0-9a-fA-F:]+$")

    private val FUNCTIONAL_LABELS = setOf(
        "push", "xmpush", "mipush", "fcm", "gcm", "mtalk", "firebase", "jpush", "getui",
        "account", "accounts", "login", "passport", "oauth", "sso", "signin",
        "update", "updater", "ota", "download", "dl",
        "pay", "mipay", "alipay", "unionpay", "wallet",
        "play", "vending", "market", "appstore",
        "cloud", "micloud", "icloud", "backup", "sync",
        "connect"
    )

    private val FUNCTIONAL_SUBSTRINGS = listOf(
        "googleapis", "gstatic", "oppomobile", "heytapmobi"
    )

    private val BLOCK_EXACT = setOf(
        "log", "logs", "ads", "ad", "mon", "px", "ut", "mam", "up4"
    )

    private val BLOCK_SUBSTRINGS = listOf(
        "applog", "errlog", "errnewlog", "rtlog", "rtapplog", "uplog", "apmlog", "aegis-log",
        "report", "monitor", "analytics", "beacon", "track", "collect", "crash", "metrics",
        "telemetry", "umeng", "appsflyer", "adjust", "oceanengine", "zijieapi", "qplus",
        "snssdk", "cnzz", "growingio", "sensorsdata", "mixpanel", "amplitude", "pangle",
        "sigmob", "adashx", "pingma", "talkingdata", "bugtrace", "huatuo", "apmplus",
        "coverage", "zxid", "oaid", "wkanx", "videoreport"
    )

    private val TEST_LABELS = setOf(
        "test", "testing", "staging", "stg", "dev", "daily", "preview", "sandbox"
    )

    fun export(apps: List<AppEntry>): DomainExport {
        val trackers = LinkedHashSet<String>()
        val review = LinkedHashSet<String>()
        val functional = LinkedHashSet<String>()

        apps.forEach { app ->
            app.uploadEndpoints.forEach { url ->
                val host = Exposure.hostOf(url) ?: return@forEach
                if (isIgnored(host) || isTestHost(host)) return@forEach
                when (classify(host)) {
                    DomainClass.TRACKER -> trackers.add(host)
                    DomainClass.REVIEW -> review.add(host)
                    DomainClass.FUNCTIONAL -> functional.add(host)
                }
            }
        }

        return DomainExport(
            trackers = trackers.sorted(),
            review = review.sorted(),
            functional = functional.sorted()
        )
    }

    fun classify(host: String): DomainClass {
        val labels = host.lowercase().split('.')
        if (labels.any { it in TEST_LABELS }) return DomainClass.REVIEW

        if (labels.any { it in FUNCTIONAL_LABELS }) return DomainClass.FUNCTIONAL
        if (FUNCTIONAL_SUBSTRINGS.any { host.contains(it) }) return DomainClass.FUNCTIONAL

        if (labels.any { it in BLOCK_EXACT }) return DomainClass.TRACKER
        if (labels.any { label -> BLOCK_SUBSTRINGS.any { label.contains(it) } }) return DomainClass.TRACKER

        // `stat` / `stats` / `statistic` are trackers; `status` is not.
        if (labels.any { it.startsWith("stat") && !it.startsWith("status") }) return DomainClass.TRACKER

        return DomainClass.REVIEW
    }

    fun isTestHost(host: String): Boolean =
        host.lowercase().split('.').any { it in TEST_LABELS }

    fun isIgnored(host: String): Boolean {
        val lower = host.lowercase()
        if (lower in IGNORED_HOSTS) return true
        if (IPV4.matches(lower)) return true
        if (lower.contains(':') && IPV6.matches(lower)) return true
        return false
    }
}

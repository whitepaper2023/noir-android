package app.noir.core.apk

import java.io.File

/** Best-effort SDK / tracker fingerprinting from domains and decompiled packages. */
object Trackers {

    private val DOMAINS: List<Pair<String, String>> = listOf(
        "umeng.com" to "Umeng",
        "appsflyer.com" to "AppsFlyer",
        "adjust.com" to "Adjust",
        "facebook.com" to "Meta",
        "facebook.net" to "Meta",
        "firebase" to "Firebase",
        "crashlytics" to "Firebase",
        "bytedance" to "ByteDance",
        "zijieapi" to "ByteDance",
        "pangle" to "ByteDance",
        "qq.com" to "Tencent",
        "tencent" to "Tencent",
        "amap.com" to "Alibaba",
        "aliyun" to "Alibaba",
        "alibaba" to "Alibaba",
        "baidu.com" to "Baidu",
        "sogou.com" to "Sogou",
        "iflytek" to "iFlytek",
        "voicecloud" to "iFlytek",
        "sentry.io" to "Sentry",
        "mixpanel" to "Mixpanel",
        "amplitude.com" to "Amplitude",
        "onesignal.com" to "OneSignal",
        "bugly.qq.com" to "Bugly",
        "growingio.com" to "GrowingIO",
        "sensorsdata" to "Sensors",
        "getui" to "Getui",
        "jpush" to "JPush",
        "xmpush" to "MiPush",
        "umengcloud" to "Umeng"
    )

    private val PACKAGES: List<Pair<String, String>> = listOf(
        "com/umeng" to "Umeng",
        "com/appsflyer" to "AppsFlyer",
        "com/adjust" to "Adjust",
        "com/facebook" to "Meta",
        "com/google/firebase" to "Firebase",
        "com/bytedance" to "ByteDance",
        "com/tencent" to "Tencent",
        "com/alibaba" to "Alibaba",
        "com/baidu" to "Baidu",
        "com/sogou" to "Sogou",
        "com/iflytek" to "iFlytek",
        "io/sentry" to "Sentry",
        "com/mixpanel" to "Mixpanel",
        "com/amplitude" to "Amplitude",
        "com/onesignal" to "OneSignal",
        "com/tencent/bugly" to "Bugly",
        "com/growingio" to "GrowingIO",
        "com/sensorsdata" to "Sensors",
        "com/igexin" to "Getui",
        "cn/jpush" to "JPush",
        "com/xiaomi/push" to "MiPush"
    )

    fun fromEndpoints(urls: List<String>): Set<String> {
        val haystack = urls.joinToString(" ").lowercase()
        return DOMAINS.filter { haystack.contains(it.first) }.map { it.second }.toSortedSet()
    }

    fun fromSources(dir: File): Set<String> {
        val root = File(dir, "sources").takeIf { it.isDirectory } ?: dir
        if (!root.isDirectory) return emptySet()
        return PACKAGES.filter { File(root, it.first).exists() }.map { it.second }.toSortedSet()
    }
}

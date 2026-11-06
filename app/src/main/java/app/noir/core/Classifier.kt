/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

enum class Category(val label: String) {
    TELEMETRY("遥测/统计"),
    ADS("广告"),
    SECURITY("安全/杀毒"),
    AI_VOICE("AI/语音"),
    LOCATION("定位/地图"),
    PUSH("推送"),
    APP_STORE("应用商店"),
    INPUT_METHOD("输入法"),
    PAYMENT("支付"),
    FINANCE("金融"),
    SHOPPING("购物"),
    MEDIA("影音"),
    SOCIAL("社交"),
    GAME("游戏"),
    TOOL("工具"),
    CARRIER("运营商"),
    SYSTEM("系统"),
    OTHER("其他")
}

/**
 * Cheap, explainable classification.
 *
 * Vendor attribution is resolved in two passes:
 *  1. the package name, which is authoritative (`com.tencent.mm` is Tencent no
 *     matter which SDK domains it embeds);
 *  2. otherwise, upload hosts scored by how many of them match a vendor suffix,
 *     with infrastructure hosts (`schemas.android.com`, `github.com`, …) ignored.
 *
 * This avoids the classic failure where every APK contains
 * `schemas.android.com/aapt` and ends up labelled Google.
 */
object Classifier {

    private val TELEMETRY = Regex(
        "(^|[./_-])(analytics|track|tracker|tracking|stat|stats|beacon|telemetry|misight|onetrack|logging)([./_-]|$)"
    )
    private val ADS = Regex(
        "(^|[./_-])(ads?|advert|advertising|doubleclick|pangle|sigmob)([./_-]|$)"
    )

    private data class VendorRule(val vendor: String, val prefixes: List<String>)

    /** Ordered: the first matching package prefix wins. */
    private val PACKAGE_RULES: List<VendorRule> = listOf(
        VendorRule("OPPO", listOf("com.heytap.", "com.oplus.", "com.coloros.", "com.oppo.", "com.nearme.")),
        VendorRule("vivo", listOf("com.vivo.", "com.bbk.")),
        VendorRule("Huawei", listOf("com.huawei.", "com.hihonor.")),
        VendorRule("Samsung", listOf("com.samsung.", "com.sec.android.")),
        VendorRule("Tencent", listOf("com.tencent.")),
        VendorRule("ByteDance", listOf("com.ss.android.", "com.bytedance.")),
        VendorRule(
            "Alibaba",
            listOf(
                "com.taobao.", "com.tmall.", "com.alibaba.", "com.alipay.",
                "com.eg.android.alipaygphone", "com.ant."
            )
        ),
        VendorRule("Baidu", listOf("com.baidu.")),
        VendorRule("Xiaomi", listOf("com.xiaomi.", "com.miui.", "com.mi.", "miui.")),
        VendorRule(
            "Google",
            listOf(
                "com.google.", "com.android.vending", "com.android.chrome",
                "com.android.gms", "com.android.gsf"
            )
        ),
        VendorRule("Meituan", listOf("com.sankuai.", "com.meituan.", "com.dianping.")),
        VendorRule("Pinduoduo", listOf("com.xunmeng.pinduoduo", "com.xunmeng.")),
        VendorRule("JD", listOf("com.jingdong.")),
        VendorRule("Bilibili", listOf("tv.danmaku.bili", "com.bilibili.")),
        VendorRule("Kuaishou", listOf("com.smile.gifmaker", "com.kuaishou.")),
        VendorRule("Weibo", listOf("com.sina.weibo")),
        VendorRule("Sogou", listOf("com.sohu.inputmethod.sogou", "com.sogou.")),
        VendorRule("iFlytek", listOf("com.iflytek.")),
        VendorRule("NetEase", listOf("com.netease.", "com.online.163.")),
        VendorRule("Mihoyo", listOf("com.mihoyo.")),
        VendorRule("UnionPay", listOf("com.unionpay.")),
        VendorRule("Umeng", listOf("com.umeng."))
    )

    private data class VendorDomain(val vendor: String, val domains: List<String>)

    private val VENDOR_DOMAINS: List<VendorDomain> = listOf(
        VendorDomain("Xiaomi", listOf("xiaomi.com", "miui.com", "mi.com", "xiaomi.net")),
        VendorDomain("OPPO", listOf("heytap.com", "heytapmobi.com", "oppomobile.com", "coloros.com", "oplus.com", "nearme.com", "wanyol.com")),
        VendorDomain("vivo", listOf("vivo.com.cn", "vivo.com", "iqoo.com")),
        VendorDomain("Huawei", listOf("huawei.com", "hicloud.com", "hihonor.com")),
        VendorDomain("Samsung", listOf("samsung.com", "samsungapps.com")),
        VendorDomain("Tencent", listOf("qq.com", "tencent.com", "tencent-cloud.net", "weixin.qq.com")),
        VendorDomain("Alibaba", listOf("alibaba.com", "aliyun.com", "taobao.com", "tmall.com", "amap.com", "ucweb.com", "uc.cn", "alipay.com")),
        VendorDomain("Baidu", listOf("baidu.com", "bdstatic.com", "bcebos.com")),
        VendorDomain("ByteDance", listOf("bytedance.com", "bytcdn.com", "zijieapi.com", "snssdk.com", "toutiao.com", "tiktokv.com", "pangle.io", "oceanengine.com")),
        VendorDomain("Google", listOf("google.com", "googleapis.com", "gstatic.com", "youtube.com", "android.com")),
        VendorDomain("Meta", listOf("facebook.com", "facebook.net", "instagram.com", "fb.com", "whatsapp.com")),
        VendorDomain("Meituan", listOf("meituan.com", "dianping.com", "sankuai.com")),
        VendorDomain("Pinduoduo", listOf("pinduoduo.com", "yangkeduo.com")),
        VendorDomain("JD", listOf("jd.com", "360buy.com")),
        VendorDomain("Bilibili", listOf("bilibili.com", "hdslb.com")),
        VendorDomain("Kuaishou", listOf("kuaishou.com", "gifshow.com")),
        VendorDomain("Weibo", listOf("weibo.com", "weibo.cn", "sina.com.cn")),
        VendorDomain("Sogou", listOf("sogou.com", "sogoucdn.com")),
        VendorDomain("iFlytek", listOf("iflytek.com", "voicecloud.cn")),
        VendorDomain("NetEase", listOf("netease.com", "163.com", "126.net")),
        VendorDomain("Mihoyo", listOf("mihoyo.com")),
        VendorDomain("UnionPay", listOf("unionpay.com", "95516.com")),
        VendorDomain("Umeng", listOf("umeng.com", "umengcloud.com")),
        VendorDomain("Firebase", listOf("firebase.com", "firebaseio.com", "crashlytics.com"))
    )

    private val KNOWN: Map<String, Category> = mapOf(
        "com.miui.analytics" to Category.TELEMETRY,
        "com.miui.misightservice" to Category.TELEMETRY,
        "com.xiaomi.security.onetrack" to Category.TELEMETRY,
        "com.miui.powerinsight" to Category.TELEMETRY,
        "com.miui.securitycenter" to Category.SECURITY,
        "com.miui.securitycore" to Category.SECURITY,
        "com.miui.securityadd" to Category.SECURITY,
        "com.miui.securitymanager" to Category.SECURITY,
        "com.miui.guardprovider" to Category.SECURITY,
        "com.bsp.catchlog" to Category.SECURITY,
        "com.miui.cleanmaster" to Category.SECURITY,
        "com.miui.systemadsolution" to Category.ADS,
        "com.xiaomi.market" to Category.APP_STORE,
        "com.android.vending" to Category.APP_STORE,
        "com.mobiletools.systemhelper" to Category.CARRIER,
        "com.xiaomi.xmsf" to Category.PUSH,
        "com.xiaomi.digitalkey" to Category.SYSTEM,
        "com.miui.screenrecorder" to Category.SYSTEM,
        "com.android.systemui" to Category.SYSTEM,
        "com.tencent.mm" to Category.SOCIAL,
        "com.tencent.mobileqq" to Category.SOCIAL,
        "com.eg.android.alipaygphone" to Category.FINANCE,
        "com.taobao.taobao" to Category.SHOPPING,
        "com.jingdong.app.mall" to Category.SHOPPING,
        "com.xunmeng.pinduoduo" to Category.SHOPPING,
        "tv.danmaku.bili" to Category.MEDIA,
        "com.google.android.youtube" to Category.MEDIA
    )

    fun vendor(pkg: String, endpoints: List<String>): String? {
        val name = pkg.lowercase()
        PACKAGE_RULES.firstOrNull { rule -> rule.prefixes.any { name.startsWith(it) } }
            ?.let { return it.vendor }

        val hosts = endpoints.mapNotNull { Exposure.hostOf(it) }
            .filterNot { Domains.isIgnored(it) }

        if (hosts.isEmpty()) return null

        val scores = HashMap<String, Int>()
        VENDOR_DOMAINS.forEach { vendor ->
            hosts.forEach { host ->
                if (vendor.domains.any { domain -> host == domain || host.endsWith(".$domain") }) {
                    scores[vendor.vendor] = (scores[vendor.vendor] ?: 0) + 1
                }
            }
        }

        return scores.maxByOrNull { it.value }?.key
    }

    fun category(
        pkg: String,
        endpoints: List<String>,
        permissions: List<String>,
        isSystem: Boolean = false
    ): Category {
        KNOWN[pkg.lowercase()]?.let { return it }

        val name = pkg.lowercase()
        val urls = endpoints.joinToString(" ").lowercase()
        val both = "$name $urls"

        fun nameHas(vararg keys: String) = keys.any { name.contains(it) }
        fun urlHas(vararg keys: String) = keys.any { urls.contains(it) }

        val matched = when {
            nameHas("dm.wo.com.cn", ".simactivate", "carrier") || urlHas("dm.wo.com.cn") -> Category.CARRIER
            nameHas("inputmethod", ".ime", "sogou", "iflytek") -> Category.INPUT_METHOD
            nameHas("market", "vending", "appstore") -> Category.APP_STORE
            nameHas("pay", "wallet", "unionpay", "mipay", ".bank") -> Category.FINANCE
            nameHas("taobao", "tmall", "jingdong", "pinduoduo", "idlefish", "meituan", "dianping", ".shop") -> Category.SHOPPING
            nameHas("bili", "youtube", "netflix", "spotify", "music", "video", "douyin", "kuaishou", "iqiyi", "youku") -> Category.MEDIA
            nameHas("browser", "chrome", "fileexplorer", "translator", "wps", "notes", "calculator", "recorder", "camera") -> Category.TOOL
            nameHas("migame", "gamecenter", "minigame", "roblox", "mihoyo", "tmgp", "game") -> Category.GAME
            nameHas("tencent.mm", "whatsapp", "instagram", "telegram", "twitter", "facebook") -> Category.SOCIAL
            TELEMETRY.containsMatchIn(both) -> Category.TELEMETRY
            ADS.containsMatchIn(both) -> Category.ADS
            nameHas("security", "guard", "antivirus", "virus", "clean", "catchlog") -> Category.SECURITY
            nameHas("voice", "asr", "speech", "xiaoai", "xiaomixiaoai", "ai.engine", "aiasst") -> Category.AI_VOICE
            nameHas("location", "lbs", "gps", "map", "amap", "metoknlp") -> Category.LOCATION
            nameHas("push", "xmsf", "firebase", "umeng", "getui", "jpush") -> Category.PUSH
            nameHas("systemui", ".settings", ".home", "launcher") -> Category.SYSTEM
            else -> null
        }

        return matched ?: if (isSystem) Category.SYSTEM else Category.OTHER
    }
}

package app.noir.core

/** A connected Android device as reported by `adb devices -l`. */
data class Device(
    val serial: String,
    val state: String,
    val model: String = "",
    val product: String = ""
)

/** Environment details that make a report reproducible. */
data class ReportMeta(
    val tool: String = "",
    val host: String = "",
    val android: String = "",
    val sdk: String = "",
    val build: String = "",
    val fingerprint: String = "",
    val user: String = "",
    val generatedAt: String = ""
)

/** One installed application, reduced to what matters for a leak audit. */
data class AppEntry(
    val pkg: String,
    val uid: String,
    val isSystem: Boolean,
    val debuggable: Boolean,
    val installer: String?,
    val permissions: List<String>,
    val endpoints: List<String>,
    val score: Int,
    val category: Category = Category.OTHER,
    val vendor: String? = null,
    val sharedUid: String? = null,
    // 1.0.7：已处置过的 appops（PROJECT_MEDIA / GET_USAGE_STATS 的子集）。
    // 处置只禁掉这两个操作，不改权限/端点；分数只扣真正被禁掉的那部分
    // （见 Rules.remediatedScore；1.0.8 起 PROJECT_MEDIA 不扣分——
    // 它只撤销常驻授权，拦不住录屏），同时 Advisor 不再重复建议同一条。
    val appliedOps: Set<String> = emptySet()
) {
    val uploadEndpoints: List<String> get() = endpoints.filter { Rules.isUpload(it) }
    val commandEndpoints: List<String> get() = endpoints.filter { Rules.isCommand(it) }

    /** Data categories this app can read. */
    val dataTypes: Set<DataType> get() = Exposure.dataTypes(permissions)

    /** Data categories + the hosts they could reach. Empty when nothing uploads. */
    val leaks: Map<DataType, List<String>> get() = Exposure.leaks(this)
}

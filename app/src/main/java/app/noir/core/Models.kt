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
    val sharedUid: String? = null
) {
    val uploadEndpoints: List<String> get() = endpoints.filter { Rules.isUpload(it) }
    val commandEndpoints: List<String> get() = endpoints.filter { Rules.isCommand(it) }

    /** Data categories this app can read. */
    val dataTypes: Set<DataType> get() = Exposure.dataTypes(permissions)

    /** Data categories + the hosts they could reach. Empty when nothing uploads. */
    val leaks: Map<DataType, List<String>> get() = Exposure.leaks(this)
}

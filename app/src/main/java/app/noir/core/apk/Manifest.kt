package app.noir.core.apk

import java.io.File
import java.util.zip.ZipFile

data class ComponentFact(
    val kind: String,
    val name: String,
    val exported: Boolean,
    val permission: String?,
    val authorities: String?,
    // 1.0.8（同步上游 0.4.2）：enabled=false 的组件不算对外暴露。
    val enabled: Boolean = true
)

/**
 * Manifest-level facts that `dumpsys` does not expose: exported components,
 * cleartext traffic, backup, debuggable, target SDK.
 *
 * The manifest is read with a small in-house AXML parser: apk-parser throws on
 * some vendor system APKs, and aapt2 is not available on user machines.
 */
data class ManifestFacts(
    val components: List<ComponentFact>,
    val allowBackup: Boolean?,
    val cleartext: Boolean?,
    val debuggable: Boolean?,
    val targetSdk: String?,
    val networkSecurityConfig: Boolean
) {
    val exportedWithoutPermission: List<ComponentFact>
        get() = components.filter { it.exported && it.enabled && it.permission == null }

    val riskyCount: Int
        get() = exportedWithoutPermission.count { it.kind in setOf("service", "receiver", "provider") }
}

object ManifestAnalyzer {

    private val COMPONENT_KINDS = setOf("activity", "activity-alias", "service", "receiver", "provider")

    fun analyze(apk: File): ManifestFacts? {
        return try {
        val bytes = ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return null
            // 1.0.8（同步上游 0.4.2）：超大 manifest 直接跳过，不读进内存。
            if (entry.size > 8L * 1024 * 1024) return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
        val nodes = Axml.parse(bytes)
        if (nodes.isEmpty()) return null

        val application = nodes.firstOrNull { it.depth == 1 && it.name == "application" }
        val sdk = nodes.firstOrNull { it.name == "uses-sdk" }
        // 1.0.8（同步上游 0.4.2）：应用级 permission 会继承到没单独声明
        // 的组件；provider 的 read/writePermission 也算数。
        val appPermission = application?.attrs?.get("permission")?.takeIf { it.isNotBlank() }
        val targetSdk = sdk?.attrs?.get("targetSdkVersion")?.toIntOrNull()

        val components = ArrayList<ComponentFact>()
        nodes.forEachIndexed { index, node ->
            if (node.depth != 2 || node.name !in COMPONENT_KINDS) return@forEachIndexed

            var hasIntentFilter = false
            var cursor = index + 1
            while (cursor < nodes.size && nodes[cursor].depth > node.depth) {
                if (nodes[cursor].name == "intent-filter") {
                    hasIntentFilter = true
                    break
                }
                cursor++
            }

            val exported = when (node.attrs["exported"]) {
                "true" -> true
                "false" -> false
                else -> hasIntentFilter
            }

            val directPermission = listOf("permission", "readPermission", "writePermission")
                .firstNotNullOfOrNull { key -> node.attrs[key]?.takeIf { it.isNotBlank() } }

            components.add(
                ComponentFact(
                    kind = node.name,
                    name = node.attrs["name"].orEmpty(),
                    exported = exported,
                    permission = directPermission ?: appPermission,
                    authorities = node.attrs["authorities"]?.takeIf { it.isNotBlank() },
                    enabled = node.attrs["enabled"] != "false"
                )
            )
        }

            ManifestFacts(
                components = components,
                // Android's default is true; a missing attribute is not "unknown".
                allowBackup = application?.let { it.attrs["allowBackup"]?.parseBool() ?: true },
                cleartext = application?.attrs?.get("usesCleartextTraffic")?.parseBool()
                    ?: targetSdk?.let { it < 28 },
                debuggable = application?.attrs?.get("debuggable")?.parseBool(),
                targetSdk = sdk?.attrs?.get("targetSdkVersion"),
                networkSecurityConfig = application?.attrs?.get("networkSecurityConfig")?.isNotBlank() == true
            )
        } catch (e: Throwable) {
            null
        }
    }

    private fun String.parseBool(): Boolean? = when (this) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

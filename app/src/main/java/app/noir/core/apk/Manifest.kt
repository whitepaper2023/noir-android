package app.noir.core.apk

import java.io.File
import java.util.zip.ZipFile

data class ComponentFact(
    val kind: String,
    val name: String,
    val exported: Boolean,
    val permission: String?,
    val authorities: String?
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
        get() = components.filter { it.exported && it.permission == null }

    val riskyCount: Int
        get() = exportedWithoutPermission.count { it.kind in setOf("service", "receiver", "provider") }
}

object ManifestAnalyzer {

    private val COMPONENT_KINDS = setOf("activity", "activity-alias", "service", "receiver", "provider")

    fun analyze(apk: File): ManifestFacts? {
        return try {
        val bytes = ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
        val nodes = Axml.parse(bytes)
        if (nodes.isEmpty()) return null

        val application = nodes.firstOrNull { it.depth == 1 && it.name == "application" }
        val sdk = nodes.firstOrNull { it.name == "uses-sdk" }

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

            components.add(
                ComponentFact(
                    kind = node.name,
                    name = node.attrs["name"].orEmpty(),
                    exported = exported,
                    permission = node.attrs["permission"]?.takeIf { it.isNotBlank() },
                    authorities = node.attrs["authorities"]?.takeIf { it.isNotBlank() }
                )
            )
        }

            ManifestFacts(
                components = components,
                allowBackup = application?.attrs?.get("allowBackup")?.parseBool(),
                cleartext = application?.attrs?.get("usesCleartextTraffic")?.parseBool(),
                debuggable = application?.attrs?.get("debuggable")?.parseBool(),
                targetSdk = sdk?.attrs?.get("targetSdkVersion"),
                networkSecurityConfig = application?.attrs?.get("networkSecurityConfig")?.isNotBlank() == true
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun String.parseBool(): Boolean? = when (this) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

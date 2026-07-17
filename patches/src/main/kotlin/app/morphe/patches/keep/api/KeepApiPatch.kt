package app.morphe.patches.keep.api

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.keep.misc.extension.sharedExtensionPatch
import app.morphe.patches.keep.shared.Constants.COMPATIBILITY_KEEP
import org.w3c.dom.Element

private const val PROVIDER_CLASS = "app.morphe.extension.keep.api.KeepApiProvider"
private const val PROVIDER_AUTHORITY = "app.morphe.keep.api"

@Suppress("unused")
val keepApiPatch = resourcePatch(
    name = "Mythara Keep API",
    description = "Adds a private API bridge for Mythara to read and manage Google Keep documents.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_KEEP)
    dependsOn(sharedExtensionPatch)

    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as Element
            val provider = document.createElement("provider").apply {
                setAttribute("android:name", PROVIDER_CLASS)
                setAttribute("android:authorities", PROVIDER_AUTHORITY)
                setAttribute("android:exported", "true")
                setAttribute("android:grantUriPermissions", "false")
            }
            application.appendChild(provider)
        }
    }
}

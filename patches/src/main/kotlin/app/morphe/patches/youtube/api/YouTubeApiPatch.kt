package app.morphe.patches.youtube.api

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import org.w3c.dom.Element

private const val PROVIDER_CLASS = "app.morphe.extension.youtube.api.YouTubeApiProvider"

// Not "app.morphe.youtube.api": that prefix is already the broadcast action namespace
// used by YouTubeControlReceiver.
private const val PROVIDER_AUTHORITY = "app.morphe.youtube.control"

@Suppress("unused")
val youtubeApiPatch = resourcePatch(
    name = "Voice Assistant YouTube API",
    description = "Adds a private API bridge for Voice Assistant to read and control YouTube playback.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)
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

package app.morphe.patches.music.api

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import org.w3c.dom.Element

private const val PROVIDER_CLASS = "app.morphe.extension.music.api.YouTubeMusicApiProvider"
private const val PROVIDER_AUTHORITY = "app.morphe.youtube.music.control"

@Suppress("unused")
val youTubeMusicApiPatch = resourcePatch(
    name = "Voice Assistant YouTube Music API",
    description = "Adds a private, caller-authenticated API bridge for Voice Assistant to read " +
        "playback state and control YouTube Music playback.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

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

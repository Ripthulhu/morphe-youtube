package app.morphe.patches.youtube.api

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.youtube.misc.auth.authHookPatch
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
    dependsOn(
        sharedExtensionPatch,
        // list_suggestions calls InnerTube. Auth is OPTIONAL there — signed-out requests still
        // return related videos — but without this hook AuthUtils never receives the account
        // headers, so every result would be the generic signed-out set. Not reached transitively
        // through sharedExtensionPatch.
        authHookPatch,
    )

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

package app.morphe.patches.youtube.interaction.aiplayback

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import org.w3c.dom.Element

private const val RECEIVER_CLASS =
    "app.morphe.extension.youtube.patches.YouTubeControlReceiver"
private const val MEDIA_START_ACTION = "app.morphe.youtube.api.MEDIA_START"

@Suppress("unused")
val aiPlaybackPatch = resourcePatch(
    name = "AI playback API",
    description = "Adds an explicit search-and-play API that opens videos in YouTube's normal player.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(sharedExtensionPatch)

    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as Element
            val receiver = document.createElement("receiver").apply {
                setAttribute("android:name", RECEIVER_CLASS)
                setAttribute("android:enabled", "true")
                setAttribute("android:exported", "true")
            }
            val intentFilter = document.createElement("intent-filter").apply {
                appendChild(document.createElement("action").apply {
                    setAttribute("android:name", MEDIA_START_ACTION)
                })
            }
            receiver.appendChild(intentFilter)
            application.appendChild(receiver)
        }
    }
}

package app.morphe.patches.music.interaction.aiplayback

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import org.w3c.dom.Element

private const val RECEIVER_CLASS =
    "app.morphe.extension.music.patches.YouTubeMusicControlReceiver"
private const val MEDIA_START_ACTION = "app.morphe.youtube.music.api.MEDIA_START"
private const val MEDIA_COMMAND_ACTION = "app.morphe.youtube.music.api.MEDIA_COMMAND"

@Suppress("unused")
val aiPlaybackPatch = resourcePatch(
    name = "AI playback API",
    description = "Adds a Symfonium-compatible search, playback, and media-control API.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

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
                appendChild(document.createElement("action").apply {
                    setAttribute("android:name", MEDIA_COMMAND_ACTION)
                })
            }
            receiver.appendChild(intentFilter)
            application.appendChild(receiver)
        }
    }
}

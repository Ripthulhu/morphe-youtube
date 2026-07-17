package app.morphe.patches.keep.misc.extension

import app.morphe.patches.all.misc.extension.sharedExtensionPatch

// Keep needs its API bridge plus the shared GmsCore compatibility implementation.
val sharedExtensionPatch = sharedExtensionPatch(listOf("keep", "shared-youtube"))

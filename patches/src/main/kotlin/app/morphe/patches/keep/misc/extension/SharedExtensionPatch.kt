package app.morphe.patches.keep.misc.extension

import app.morphe.patches.all.misc.extension.sharedExtensionPatch

// The API provider is initialized by Android, but the extension must still be merged into Keep.
val sharedExtensionPatch = sharedExtensionPatch(listOf("keep"))

package app.morphe.patches.keep.misc.gms

import app.morphe.patches.keep.misc.extension.sharedExtensionPatch
import app.morphe.patches.keep.misc.gms.Constants.KEEP_PACKAGE_NAME
import app.morphe.patches.keep.misc.gms.Constants.KEEP_PACKAGE_SIGNATURE
import app.morphe.patches.keep.misc.gms.Constants.MORPHE_KEEP_PACKAGE_NAME
import app.morphe.patches.keep.shared.Constants.COMPATIBILITY_KEEP
import app.morphe.patches.shared.misc.gms.gmsCoreSupportPatch

@Suppress("unused")
val gmsCoreSupportPatch = gmsCoreSupportPatch(
    fromPackageName = KEEP_PACKAGE_NAME,
    toPackageNameDefault = MORPHE_KEEP_PACKAGE_NAME,
    extensionPatch = sharedExtensionPatch,
    gmsCoreSupportResourcePatchFactory = ::gmsCoreSupportResourcePatch,
) {
    compatibleWith(COMPATIBILITY_KEEP)
}

private fun gmsCoreSupportResourcePatch() =
    app.morphe.patches.shared.misc.gms.gmsCoreSupportResourcePatch(
        fromPackageName = KEEP_PACKAGE_NAME,
        toPackageNameDefault = MORPHE_KEEP_PACKAGE_NAME,
        spoofedPackageSignature = KEEP_PACKAGE_SIGNATURE,
        screen = null,
    )

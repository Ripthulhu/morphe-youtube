package app.morphe.patches.keep.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_KEEP = Compatibility(
        name = "Google Keep",
        packageName = "com.google.android.keep",
        apkFileType = ApkFileType.APK_REQUIRED,
        targets = listOf(
            AppTarget(
                version = "5.26.281.02.90",
                versionCode = 220667747,
                minSdk = 26,
            )
        )
    )
}

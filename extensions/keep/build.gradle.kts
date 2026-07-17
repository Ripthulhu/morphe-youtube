import com.android.build.api.dsl.ApplicationExtension

dependencies {
    compileOnly(libs.morphe.extensions.library)
}

configure<ApplicationExtension> {
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
}

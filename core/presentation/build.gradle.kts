plugins {
    id("filmax.android.compose")
}

android {
    namespace = "com.filmax.core.presentation"
}

dependencies {
    api(project(":core:domain"))
    val bom = platform(libs.compose.bom)
    api(bom)
    api(libs.bundles.compose)
    api(libs.kotlinx.coroutines.core)
}

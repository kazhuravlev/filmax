plugins {
    id("filmax.android.compose")
}

android { namespace = "com.filmax.core.tv.designsystem" }

dependencies {
    api(project(":core:designsystem"))
    api(libs.tv.material)

    val bom = platform(libs.compose.bom)
    implementation(bom)
    implementation(libs.bundles.compose)
}

plugins {
    id("filmax.android.compose")
}

android {
    namespace = "com.filmax.core.ui"
}

dependencies {
    api(project(":core:designsystem"))
    implementation(project(":core:domain"))

    val bom = platform(libs.compose.bom)
    implementation(bom)
    implementation(libs.bundles.compose)
    implementation(libs.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    // koinInject() внутри @Composable (PosterImage/TvActorCard читают ImageProxyRepository) —
    // koin-android этого не даёт, нужен именно compose-модуль.
    implementation(libs.koin.androidx.compose)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

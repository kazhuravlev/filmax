plugins {
    id("filmax.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

android { namespace = "com.filmax.feature.details.tv" }

dependencies {
    api(project(":feature:details:common"))

    implementation(project(":core:ui"))
    implementation(project(":core:tv-designsystem"))
    implementation(project(":core:presentation"))
    implementation(project(":core:domain"))

    val bom = platform(libs.compose.bom)
    implementation(bom)
    implementation(libs.bundles.compose)
    implementation(libs.navigation.compose)
    // Прямой Coil нужен только для аватара актёра: PosterImage при ошибке рисует значок «фото
    // нет» (правильно для настоящих постеров), а угаданное по MD5 имени фото должно тихо
    // откатываться на инициалы — см. TvActorCard в TvDetailsScreen.kt.
    implementation(libs.coil.compose)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.androidx.compose)
}

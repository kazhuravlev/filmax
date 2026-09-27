plugins {
    id("filmax.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.filmax.core.network"
    buildFeatures { buildConfig = true }
}

kotlin {
    val koinBom = project.dependencies.platform(libs.koin.bom)
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:domain"))

            api(libs.bundles.ktor.common)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.multiplatform.settings)

            api(koinBom)
            api(libs.koin.core)
        }
        androidMain.dependencies {
            api(libs.ktor.client.okhttp)
            api(libs.kotlinx.coroutines.android)

            api(koinBom)
            api(libs.koin.android)
        }
        appleMain.dependencies {
            api(libs.ktor.client.darwin)
        }
    }
}

dependencies {
    "debugImplementation"(libs.chucker)
    "releaseImplementation"(libs.chucker.no.op)
}

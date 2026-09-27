import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("filmax.detekt")
}

val googleServicesConfig = file("google-services.json")
if (googleServicesConfig.exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)

    val registeredPackages = Regex("\"package_name\"\\s*:\\s*\"([^\"]+)\"")
        .findAll(googleServicesConfig.readText())
        .map { it.groupValues[1] }
        .toSet()
    val variantPackages = mapOf(
        "Debug" to "com.filmax.app.debug",
        "Demo" to "com.filmax.app.demo",
        "Release" to "com.filmax.app",
    )
    val unregisteredVariants = variantPackages.filterValues { it !in registeredPackages }.keys
    tasks.matching { task ->
        val firebaseTask = task.name.contains("GoogleServices") || task.name.contains("Crashlytics")
        firebaseTask && unregisteredVariants.any { variantName -> task.name.contains(variantName) }
    }.configureEach { enabled = false }
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use(::load)
}
fun signingSecret(envName: String, propName: String): String? =
    System.getenv(envName) ?: keystoreProps.getProperty(propName)

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val tmdbApiKey: String = (System.getenv("TMDB_API_KEY") ?: localProps.getProperty("tmdb.apiKey") ?: "").trim()

val demoAccessToken: String = (localProps.getProperty("demo.accessToken") ?: "").trim()
val demoRefreshToken: String = (localProps.getProperty("demo.refreshToken") ?: "").trim()

fun githubRepository(): String {
    fun validSlug(value: String): String? =
        value.trim().takeIf { Regex("^[^/\\s]+/[^/\\s]+$").matches(it) }

    validSlug(System.getenv("GITHUB_REPOSITORY") ?: "")?.let { return it }

    val remote = providers.exec {
        commandLine("git", "config", "--get", "remote.origin.url")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().removeSuffix(".git")
    return Regex("github\\.com[:/]([^/]+/[^/]+)$").find(remote)?.groupValues?.get(1).orEmpty()
}

val updateGithubToken: String =
    (System.getenv("UPDATE_GITHUB_TOKEN") ?: localProps.getProperty("github.updateToken") ?: "").trim()

fun gitVersionName(): String =
    providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0", "origin/main")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().removePrefix("v").ifEmpty { "1.0.0" }

fun gitCommitCount(): Int =
    providers.exec {
        commandLine("git", "rev-list", "--count", "origin/main")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull() ?: 1

android {
    namespace   = "com.filmax.app"
    compileSdk  = 35

    defaultConfig {
        applicationId = "com.filmax.app"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = gitCommitCount()
        versionName   = gitVersionName()
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "DEMO_ACCESS_TOKEN", "\"\"")
        buildConfigField("String", "DEMO_REFRESH_TOKEN", "\"\"")
        buildConfigField("String", "UPDATE_GITHUB_REPO", "\"${githubRepository()}\"")
        buildConfigField("String", "UPDATE_GITHUB_TOKEN", "\"$updateGithubToken\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingSecret("KEYSTORE_FILE", "storeFile")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = signingSecret("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingSecret("KEY_ALIAS", "keyAlias")
                keyPassword = signingSecret("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfigs.getByName("release").takeIf { it.storeFile?.exists() == true }
                ?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("demo") {
            initWith(getByName("release"))
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            matchingFallbacks += "release"
            signingConfigs.getByName("release").takeIf { it.storeFile?.exists() == true }
                ?.let { signingConfig = it }
            buildConfigField("String", "DEMO_ACCESS_TOKEN", "\"$demoAccessToken\"")
            buildConfigField("String", "DEMO_REFRESH_TOKEN", "\"$demoRefreshToken\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = JvmTarget.JVM_17.target }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core:network"))
    implementation(project(":core:domain"))
    implementation(project(":core:tv-designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:presentation"))

    implementation(project(":data:auth"))
    implementation(project(":data:catalog"))
    implementation(project(":data:search"))
    implementation(project(":data:user"))
    implementation(project(":data:watching"))
    implementation(project(":data:tmdb"))

    implementation(project(":feature:onboarding:common"))
    implementation(project(":feature:onboarding:tv"))
    implementation(project(":feature:home:common"))
    implementation(project(":feature:home:tv"))
    implementation(project(":feature:search:common"))
    implementation(project(":feature:search:tv"))
    implementation(project(":feature:collections:common"))
    implementation(project(":feature:collections:tv"))
    implementation(project(":feature:library:common"))
    implementation(project(":feature:library:tv"))
    implementation(project(":feature:profile:common"))
    implementation(project(":feature:profile:tv"))
    implementation(project(":feature:details:common"))
    implementation(project(":feature:details:tv"))
    implementation(project(":feature:player:common"))
    implementation(project(":feature:player:tv"))

    // Compose
    val bom = platform(libs.compose.bom)
    implementation(bom)
    implementation(libs.bundles.compose)
    implementation(libs.activity.compose)

    implementation(libs.profileinstaller)

    // Navigation
    implementation(libs.navigation.compose)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)

    // Koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
}

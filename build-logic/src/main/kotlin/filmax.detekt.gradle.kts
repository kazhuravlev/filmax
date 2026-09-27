import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("io.gitlab.arturbosch.detekt")
}

extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    source.setFrom(
        "src/main/kotlin",
        "src/main/java",
        "src/test/kotlin",
        "src/commonMain/kotlin",
        "src/androidMain/kotlin",
        "src/appleMain/kotlin",
        "src/iosMain/kotlin",
        "src/commonTest/kotlin",
    )
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "detektPlugins"(versionCatalog.findLibrary("detekt-formatting").get())
    if (path != ":detekt-rules") {
        "detektPlugins"(project(":detekt-rules"))
    }
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

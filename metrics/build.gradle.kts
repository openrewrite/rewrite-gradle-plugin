import nl.javadude.gradle.plugins.license.LicenseExtension
import java.util.*

plugins {
    `groovy-gradle-plugin`
    id("com.github.hierynomus.license") version "0.16.1"
}

repositories {
    if (!project.hasProperty("releasing")) {
        mavenLocal()
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots")
        }
    }

    mavenCentral()
}

dependencies {
    implementation("io.micrometer:micrometer-core:latest.release")
    implementation("com.google.guava:guava:latest.release")
}

configure<LicenseExtension> {
    ext.set("year", Calendar.getInstance().get(Calendar.YEAR))
    skipExistingHeaders = true
    header = project.rootProject.file("gradle/licenseHeader.txt")
    mapping(mapOf("kt" to "SLASHSTAR_STYLE", "java" to "SLASHSTAR_STYLE"))
    strictCheck = true
    exclude("**/versions.properties")
    exclude("**/*.txt")
}

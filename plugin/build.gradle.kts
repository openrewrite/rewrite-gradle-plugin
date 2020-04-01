import org.gradle.rewrite.build.GradleVersionData
import org.gradle.rewrite.build.GradleVersionsCommandLineArgumentProvider
import java.net.URI

plugins {
    java
    groovy
    `java-gradle-plugin`
    `maven-publish`
    checkstyle
    codenarc
    `kotlin-dsl`
    id("nebula.maven-resolved-dependencies") version "17.2.1"
    id("com.github.johnrengelman.shadow") version "5.2.0"
}

repositories {
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases/") }
    jcenter()
}

configurations.all {
    resolutionStrategy {
        cacheChangingModulesFor(0, TimeUnit.SECONDS)
        cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
    }
}

group = "org.gradle"
description = "Refactor source code automatically"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val plugin: Configuration by configurations.creating

configurations.getByName("compileOnly").extendsFrom(plugin)

dependencies {
    plugin("org.gradle.rewrite:rewrite-java:latest.integration")
    plugin("org.gradle.rewrite.plan:rewrite-checkstyle:latest.integration")
    plugin("org.eclipse.jgit:org.eclipse.jgit:latest.release")

    api("org.gradle.rewrite:rewrite-java:latest.integration")
    api("org.gradle.rewrite.plan:rewrite-checkstyle:latest.integration")
    api("org.eclipse.jgit:org.eclipse.jgit:latest.release")

    testImplementation(gradleTestKit())
    testImplementation("org.codehaus.groovy:groovy-all:2.5.8")
    testImplementation("org.spockframework:spock-core:1.3-groovy-2.5")
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(plugin)
}

publishing {
    publications {
        create<MavenPublication>("plugin") {
            artifactId = "rewrite-gradle-plugin"
            artifact(tasks.named<Jar>("jar").get())
        }
    }
    repositories {
        maven {
            name = "GradleReleases"
            url = URI.create("https://repo.gradle.org/gradle/libs-releases-local")
            credentials {
                username = project.findProperty("artifactoryUsername") as String?
                password = project.findProperty("artifactoryPassword") as String?
            }
        }
    }
}

tasks.named<Test>("test") {
    systemProperty(
        GradleVersionsCommandLineArgumentProvider.PROPERTY_NAME,
        project.findProperty("testedGradleVersion") ?: gradle.gradleVersion
    )
}

tasks.register<Test>("testGradleReleases") {
    jvmArgumentProviders.add(GradleVersionsCommandLineArgumentProvider(GradleVersionData::getReleasedVersions))
}

tasks.register<Test>("testGradleNightlies") {
    jvmArgumentProviders.add(GradleVersionsCommandLineArgumentProvider(GradleVersionData::getNightlyVersions))
}

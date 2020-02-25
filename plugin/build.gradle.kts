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
    id("com.gradle.plugin-publish") version "0.10.1"
}

repositories {
    jcenter()
    mavenLocal()
    maven { url = uri("https://oss.jfrog.org/artifactory/oss-snapshot-local") }
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
    plugin("com.netflix.devinsight.rewrite:rewrite-core:latest.integration")
    plugin("eu.infomas:annotation-detector:latest.release")
    plugin("org.gradle:rewrite-checkstyle:latest.integration")
    plugin("org.gradle:rewrite-spring:latest.integration")
    plugin("org.springframework:spring-beans:5.2.3.RELEASE")

    testImplementation(gradleTestKit())
    testImplementation("org.codehaus.groovy:groovy-all:2.5.8")
    testImplementation("org.spockframework:spock-core:1.3-groovy-2.5")
}

gradlePlugin {
    plugins {
        create("rewrite") {
            id = "org.gradle.rewrite"
            displayName = "Gradle rewrite plugin"
            description = project.description
            implementationClass = "org.gradle.rewrite.RewritePlugin"
        }
    }
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(plugin)
}

pluginBundle {
    website = "https://github.com/gradle/rewrite-gradle-plugin"
    vcsUrl = "https://github.com/gradle/rewrite-gradle-plugin.git"
    description = project.description
    tags = listOf("static-analysis", "refactoring")

    mavenCoordinates {
        groupId = "org.gradle"
        artifactId = "rewrite-gradle-plugin"
    }
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
            name = "GradleBuildInternalSnapshots"
            url = URI.create("https://repo.gradle.org/gradle/libs-snapshots-local")
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

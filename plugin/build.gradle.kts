//import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.rewrite.build.GradleVersionData
import org.gradle.rewrite.build.GradleVersionsCommandLineArgumentProvider
import java.net.URI

plugins {
    java
    groovy
    `java-gradle-plugin`
    checkstyle
    codenarc
    `kotlin-dsl`
//    id("com.github.johnrengelman.shadow") version "5.2.0"
    id("nebula.maven-publish") version "17.2.1"
    id("nebula.maven-resolved-dependencies") version "17.2.1"
//    id("nebula.maven-shadow-publish") version "17.2.1"
}

repositories {
    maven {
        url = uri("https://repo.gradle.org/gradle/enterprise-libs-releases-local/")
        credentials {
            username = project.findProperty("artifactoryUsername") as String
            password = project.findProperty("artifactoryPassword") as String
        }
        authentication {
            create<BasicAuthentication>("basic")
        }
    }
    maven {
        url = uri("https://repo.gradle.org/gradle/enterprise-libs-snapshots-local/")
        credentials {
            username = project.findProperty("artifactoryUsername") as String
            password = project.findProperty("artifactoryPassword") as String
        }
        authentication {
            create<BasicAuthentication>("basic")
        }
    }
    mavenCentral()
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
    
    // needed to execute tests in IDE, command line before shading
//    runtimeOnly("org.eclipse.jgit:org.eclipse.jgit:latest.release")

    testImplementation(gradleTestKit())
    testImplementation("org.codehaus.groovy:groovy-all:2.5.8")
    testImplementation("org.spockframework:spock-core:1.3-groovy-2.5")
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(plugin)
}

fun shouldUseReleaseRepo(): Boolean {
    return project.gradle.startParameter.taskNames.contains("final") || project.gradle.startParameter.taskNames.contains(":final")
}

project.gradle.taskGraph.whenReady(object : Action<TaskExecutionGraph> {
    override fun execute(graph: TaskExecutionGraph) {
        if (graph.hasTask(":snapshot") || graph.hasTask(":immutableSnapshot")) {
            throw GradleException("You cannot use the snapshot or immutableSnapshot task from the release plugin. Please use the devSnapshot task.")
        }
    }
})

//tasks.named<ShadowJar>("shadowJar") {
//    archiveClassifier.set(null as String?) // this configuration is used to produce only the shadowed jar
//    relocate("org.eclipse.jgit", "org.gradle.rewrite.plugin.org.eclipse.jgit")
//    dependencies {
//        include(dependency("org.eclipse.jgit:org.eclipse.jgit:.*"))
//    }
//}

publishing {
    publications.named<MavenPublication>("nebula") {
        artifactId = "rewrite-gradle-plugin"
    }
//    publications.named<MavenPublication>("nebula") {
//        pom.withXml {
//            val dependencies = asElement().getElementsByTagName("dependencies").item(0)
//            val artifactIds = asElement().getElementsByTagName("artifactId")
//            for (i in 0 until artifactIds.length) {
//                val artifactId = artifactIds.item(i)
//                if (artifactId.textContent == "org.eclipse.jgit") {
//                    dependencies.removeChild(artifactId.parentNode)
//                    break
//                }
//            }
//        }
//    }
    repositories {
        maven {
            name = "GradleEnterprise"
            url = if (shouldUseReleaseRepo()) {
                URI.create("https://repo.gradle.org/gradle/enterprise-libs-releases-local")
            } else {
                URI.create("https://repo.gradle.org/gradle/enterprise-libs-snapshots-local")
            }
            credentials {
                username = project.findProperty("artifactoryUsername") as String?
                password = project.findProperty("artifactoryPassword") as String?
            }
        }
    }
}

project.rootProject.tasks.getByName("postRelease").dependsOn(project.tasks.getByName("publishNebulaPublicationToGradleEnterpriseRepository"))

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

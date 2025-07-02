plugins {
    id("nebula.release") version "17.1.0"
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
    id("org.owasp.dependencycheck") version "latest.release" apply false
    id("nebula.maven-resolved-dependencies") version "18.4.0" apply false
    id("nebula.maven-apache-license") version "18.4.0" apply false
}

configure<nebula.plugin.release.git.base.ReleasePluginExtension> {
    defaultVersionStrategy = nebula.plugin.release.NetflixOssStrategies.SNAPSHOT(project)
}

allprojects {
    apply(plugin = "org.owasp.dependencycheck")
    apply(plugin = "nebula.maven-resolved-dependencies")

    group = "org.openrewrite"
    description = "Eliminate Tech-Debt. At build time."

    configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
        analyzers.assemblyEnabled = false
        failBuildOnCVSS = 9.0F
        suppressionFile = "suppressions.xml"
        nvd.apiKey = System.getenv("NVD_API_KEY")
    }

    dependencies{
        modules {
            module("com.google.guava:listenablefuture") {
                replacedBy("com.google.guava:guava", "listenablefuture is part of guava")
            }
            module("com.google.collections:google-collections") {
                replacedBy("com.google.guava:guava", "google-collections is part of guava")
            }
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}

evaluationDependsOn(":plugin")

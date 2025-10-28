plugins {
    id("nebula.release") version "latest.release"
    id("io.github.gradle-nexus.publish-plugin") version "latest.release"
    id("org.owasp.dependencycheck") version "latest.release" apply false
    id("nebula.maven-resolved-dependencies") version "latest.release" apply false
    id("nebula.maven-apache-license") version "latest.release" apply false
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
        analyzers.centralEnabled = System.getenv("CENTRAL_ANALYZER_ENABLED").toBoolean()
        analyzers.ossIndex.username = System.getenv("OSSINDEX_USERNAME")
        analyzers.ossIndex.password = System.getenv("OSSINDEX_PASSWORD")
        analyzers {
            nodeAudit {
                enabled = false
                yarnEnabled = false
            }
        }
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

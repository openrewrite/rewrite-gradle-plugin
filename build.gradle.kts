plugins {
    id("nebula.release") version "16.0.0"
    id("io.github.gradle-nexus.publish-plugin") version "1.0.0"
    id("org.owasp.dependencycheck") version "7.1.0.1" apply false
    id("nebula.maven-resolved-dependencies") version "18.2.0" apply false
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
    }
}

nexusPublishing {
    repositories {
        sonatype()
    }
}

evaluationDependsOn(":plugin")

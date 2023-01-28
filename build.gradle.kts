plugins {
    id("nebula.release") version "17.1.0"
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
    id("org.owasp.dependencycheck") version "8.0.2" apply false
    id("nebula.maven-resolved-dependencies") version "18.4.0" apply false
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

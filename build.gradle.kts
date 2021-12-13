plugins {
    id("nebula.release") version "15.3.1"
    id("io.github.gradle-nexus.publish-plugin") version "1.0.0"
}

configure<nebula.plugin.release.git.base.ReleasePluginExtension> {
    defaultVersionStrategy = nebula.plugin.release.NetflixOssStrategies.SNAPSHOT(project)
}

allprojects {
    group = "org.openrewrite"
    description = "Eliminate Tech-Debt. At build time."
}

nexusPublishing {
    repositories {
        sonatype()
    }
}

evaluationDependsOn(":plugin")

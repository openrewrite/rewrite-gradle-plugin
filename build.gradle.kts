plugins {
    id("nebula.release") version "15.3.1"
}

configure<nebula.plugin.release.git.base.ReleasePluginExtension> {
    defaultVersionStrategy = nebula.plugin.release.NetflixOssStrategies.SNAPSHOT(project)
}

allprojects {
    group = "org.openrewrite"
    description = "Eliminate Tech-Debt. At build time."
}

evaluationDependsOn(":plugin")

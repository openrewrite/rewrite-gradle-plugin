plugins {
    id("nebula.release") version "13.2.1"
}

buildScan {
    val buildUrl = System.getenv("BUILD_URL") ?: ""
    if (buildUrl.isNotBlank()) {
        link("Build URL", buildUrl)
    }
}

group = "org.gradle.rewrite"
description = "Automatically refactor source"

evaluationDependsOn("plugin")

tasks.named("releaseCheck") {
    doFirst {
        if(!JavaVersion.current().isJava8) {
            throw GradleException("Plugin releases should use Java 8.")
        }
    }
}

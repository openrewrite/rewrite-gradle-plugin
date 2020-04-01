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

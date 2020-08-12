plugins {
    id("nebula.release") version "13.2.1"
}

allprojects {
    group = "org.openrewrite"
    description = "Eliminate Tech-Debt. At build time."
}

evaluationDependsOn("plugin")

tasks.register("downloadDependencies") {
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach {
            it.files
        }
    }
}

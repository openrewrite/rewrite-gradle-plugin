package org.openrewrite.gradle.fixtures

class GradleFixtures {
    companion object {
        //language=groovy
        const val REPOSITORIES = """
            repositories {
                mavenLocal()
                mavenCentral()
                maven {
                    url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                }
            }
        """

        //language=groovy
        const val REWRITE_BUILD_GRADLE = """
            plugins {
                id("java")
                id("org.openrewrite.rewrite")
            }
        """ + REPOSITORIES
    }
}
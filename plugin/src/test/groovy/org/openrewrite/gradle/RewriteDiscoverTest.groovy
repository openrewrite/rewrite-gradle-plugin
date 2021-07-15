/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle

import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Issue
import spock.lang.Unroll

@Unroll
class RewriteDiscoverTest extends RewriteTestBase {

    def "rewriteDiscover will print some stuff"() {
        given:
        new File(projectDir, "settings.gradle").createNewFile()
        File rewriteYaml = new File(projectDir, "rewrite-config.yml")
        rewriteYaml.text = rewriteYamlText

        File buildGradleFile = new File(projectDir, "build.gradle")
        buildGradleFile.text = buildGradleFileText

        when:
        def result = gradleRunner(gradleVersion, "rewriteDiscover").build()
        def rewriteDiscoverResult = result.task(":rewriteDiscover")

        then:
        rewriteDiscoverResult.outcome == TaskOutcome.SUCCESS

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    @Issue("https://github.com/openrewrite/rewrite-gradle-plugin/issues/33")
    def "rewriteDiscover handles deserializing third-party dependencies"() {
        given:
        new File(projectDir, "settings.gradle")

        File buildGradleFile = new File(projectDir, "build.gradle")
        buildGradleFile.text = """\
                plugins {
                    id("java")
                    id("org.openrewrite.rewrite")
                }
                
                repositories {
                    mavenLocal()
                    mavenCentral()
                    maven {
                        url = uri("https://oss.sonatype.org/content/repositories/snapshots")
                    }
                }

                dependencies {
                    compileOnly("org.openrewrite.recipe:rewrite-testing-frameworks:1.1.0")
                }
                
                rewrite {
                     activeRecipe("org.openrewrite.java.testing.junit5.JUnit5BestPractices")
                     activeRecipe("org.openrewrite.java.format.AutoFormat")
                     activeStyle("org.openrewrite.java.SpringFormat")
                }
            """.stripIndent()

        when:
        def result = gradleRunner(gradleVersion, "rewriteDiscover").build()
        def rewriteDiscoverResult = result.task(":rewriteDiscover")

        then:
        rewriteDiscoverResult.outcome == TaskOutcome.SUCCESS

        // this assertion string containing total number of discovered recipes will change over time, it should be replaced, but it's confidence-instilling for the moment TODO
        result.output.contains("Configured with 2 active recipes and 1 active styles.")
        !result.output.contains("Could not resolve type id")

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }
}

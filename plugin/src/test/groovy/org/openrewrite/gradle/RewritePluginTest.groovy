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

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.TaskOutcome

class RewritePluginTest extends RewriteTestBase {

    String rewriteYamlText =  """\
            ---
            type: specs.openrewrite.org/v1beta/visitor
            name: org.openrewrite.gradle.SayHello
            visitors:
              - org.openrewrite.java.ChangeMethodName:
                  method: org.openrewrite.gradle.HelloWorld sayGoodbye()
                  name: sayHello
            ---
            type: specs.openrewrite.org/v1beta/recipe
            name: org.openrewrite.testProfile
            include:
              - 'org.openrewrite.gradle.SayHello'
            """.stripIndent()
    String buildGradleFileText = """\
            plugins {
                id("java")
                id("org.openrewrite.rewrite")
            }
            
            rewrite {
                configFile = "rewrite-config.yml"
                activeRecipe("org.openrewrite.testProfile")
            }
            """.stripIndent()
    String HelloWorldJavaBeforeRefactor = """\
            package org.openrewrite.gradle;
            
            public class HelloWorld {
                public static void sayGoodbye() {
                    System.out.println("Hello world");
                }
                public static void main(String[] args) {
                    sayGoodbye();
                }
            }
            """.stripIndent()
    String HelloWorldJavaAfterRefactor = """\
            package org.openrewrite.gradle;
            
            public class HelloWorld {
                public static void sayHello() {
                    System.out.println("Hello world");
                }
                public static void main(String[] args) {
                    sayHello();
                }
            }
            """.stripIndent()


    def "rewriteWarn task will run as part of a normal Java Build"() {
        given:
        projectDir.newFile("settings.gradle")
        File rewriteYaml = projectDir.newFile("rewrite-config.yml")
        rewriteYaml.text = rewriteYamlText

        File buildGradleFile = projectDir.newFile("build.gradle")
        buildGradleFile.text = buildGradleFileText
        File sourceFile = writeSource(HelloWorldJavaBeforeRefactor)

        when:
        def result = gradleRunner("6.5.1", "build").build()

        def rewriteWarnMainResult = result.task(":rewriteWarnMain")
        def rewriteWarnTestResult = result.task(":rewriteWarnTest")


        then:
        rewriteWarnMainResult.outcome == TaskOutcome.SUCCESS
        // The "warn" task should not have touched the source file and the "fix" task shouldn't have run
        sourceFile.text == HelloWorldJavaBeforeRefactor
        // With no test source in this project any of these are potentially reasonable results
        // Ultimately NO_SOURCE is probably the most appropriate, but in this early stage of development SUCCESS is acceptable
        rewriteWarnTestResult.outcome == TaskOutcome.SUCCESS || rewriteWarnTestResult.outcome == TaskOutcome.NO_SOURCE || rewriteWarnTestResult.outcome == TaskOutcome.SKIPPED
    }

    def "rewriteFix will alter the source file according to the provided active profile"() {
        given:
        projectDir.newFile("settings.gradle")
        File rewriteYaml = projectDir.newFile("rewrite-config.yml")
        rewriteYaml.text = rewriteYamlText

        File buildGradleFile = projectDir.newFile("build.gradle")
        buildGradleFile.text = buildGradleFileText
        File sourceFile = writeSource(HelloWorldJavaBeforeRefactor)

        when:
        def result = gradleRunner("6.5.1", "rewriteFixMain").build()
        def rewriteFixMainResult = result.task(":rewriteFixMain")

        then:
        rewriteFixMainResult.outcome == TaskOutcome.SUCCESS
        sourceFile.text == HelloWorldJavaAfterRefactor
    }

    def "rewriteDiscover"() {
        given:
        projectDir.newFile("settings.gradle")
        File rewriteYaml = projectDir.newFile("rewrite-config.yml")
        rewriteYaml.text = rewriteYamlText

        File buildGradleFile = projectDir.newFile("build.gradle")
        buildGradleFile.text = buildGradleFileText
        File sourceFile = writeSource(HelloWorldJavaBeforeRefactor)

        when:
        Project project = ProjectBuilder.builder()
                .withProjectDir(projectDir.getRoot())
                .build()

        RewriteDiscoverTask rewriteDiscoverTask = project.tasks.getByName("rewriteDiscoverMain") as RewriteDiscoverTask
        rewriteDiscoverTask.run()

        then:
        true
    }

    def "rewriteDiscover will print some stuff"() {
        given:
        projectDir.newFile("settings.gradle")
        File rewriteYaml = projectDir.newFile("rewrite-config.yml")
        rewriteYaml.text = rewriteYamlText

        File buildGradleFile = projectDir.newFile("build.gradle")
        buildGradleFile.text = buildGradleFileText
        File sourceFile = writeSource(HelloWorldJavaBeforeRefactor)

        when:
        def result = gradleRunner("6.5.1", "rewriteDiscoverMain").build()
        def rewriteDiscoverResult = result.task(":rewriteDiscoverMain")

        then:
        rewriteDiscoverResult.outcome == TaskOutcome.SUCCESS
    }
}

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

class RewritePluginTest extends RewriteTestBase {

    def "rewriteWarn task will run as part of a normal Java Build"() {
        given:
        projectDir.newFile("settings.gradle")
        File buildGradleFile = projectDir.newFile("build.gradle")
        buildGradleFile.text = """\
            plugins {
                id("java")
                id("org.openrewrite.rewrite")
            }
            
            rewrite {
            
            }
            """.stripIndent()
        writeSource("""\
            package org.openrewrite.gradle;
            
            public class HelloWorld {
                public static void sayHello() {
                    System.out.println("Hello world");
                }
            }
            """.stripIndent())

        def runner = gradleRunner("6.5.1", "build")

        when:
        def result = runner.build()

        def rewriteWarnMainResult = result.task(":rewriteWarnMain")
        def rewriteWarnTestResult = result.task(":rewriteWarnTest")


        then:
        rewriteWarnMainResult.outcome == TaskOutcome.SUCCESS
        // With no test source in this project any of these are potentially reasonable results
        // Ultimately NO_SOURCE is probably the most appropriate, but in this early stage of development SUCCESS is acceptable
        rewriteWarnTestResult.outcome == TaskOutcome.SUCCESS || rewriteWarnTestResult.outcome == TaskOutcome.NO_SOURCE || rewriteWarnTestResult.outcome == TaskOutcome.SKIPPED
    }
}

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
import spock.lang.Unroll

@Unroll
class RewriteDryRunTest extends RewriteTestBase {

    def "rewriteDryRun task runs successfully as a standalone command without modifying source files"() {
        given:
        projectDir.newFile("settings.gradle")
        File rewriteYaml = projectDir.newFile("rewrite-config.yml")
        rewriteYaml.text = rewriteYamlText

        File buildGradleFile = projectDir.newFile("build.gradle")
        buildGradleFile.text = buildGradleFileText
        File sourceFile = writeSource(helloWorldJavaBeforeRefactor)

        when:
        def result = gradleRunner(gradleVersion, "rewriteDryRun").build()
        def rewriteDryRunResult = result.task(":rewriteDryRun")

        then:
        rewriteDryRunResult.outcome == TaskOutcome.SUCCESS
        // The "rewriteDryRun" task should not have touched the source file and the "rewriteRun" task shouldn't have run
        sourceFile.text == helloWorldJavaBeforeRefactor

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

}

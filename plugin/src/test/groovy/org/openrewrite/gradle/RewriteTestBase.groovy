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

import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

import java.lang.management.ManagementFactory

class RewriteTestBase extends Specification {
    static final List<String> GRADLE_VERSIONS_UNDER_TEST = gradleVersionsUnderTest()

    @Rule
    TemporaryFolder projectDir = new TemporaryFolder()

    @Rule
    TemporaryFolder tempDir = new TemporaryFolder()

    @SuppressWarnings("GroovyAssignabilityCheck")
    File writeSource(String source, String sourceSet = "main") {
        String packageName = (source =~ /package\s+([\w.]+)/)[0][1]
        String className = (source =~ /(class|interface)\s+(\w+)\s+/)[0][2]
        String sourceFilePackage = "src/$sourceSet/java/${packageName.replace('.', '/')}"
        new File(projectDir.root, sourceFilePackage).mkdirs()
        def file = projectDir.newFile("$sourceFilePackage/${className}.java")
        file << source
        return file
    }

    GradleRunner gradleRunner(String gradleVersion, String... tasks) {
        GradleRunner.create()
                .withDebug(ManagementFactory.getRuntimeMXBean().getInputArguments().toString().indexOf("-agentlib:jdwp") > 0)
                .withProjectDir(projectDir.root)
                .withArguments((tasks + '--full-stacktrace').toList())
                .withTestKitDir(tempDir.getRoot())
                .withPluginClasspath()
                .forwardOutput()
                .tap {
                    if(gradleVersion != null) {
                        withGradleVersion(gradleVersion)
                    }
                }
    }

    static private List<String> gradleVersionsUnderTest() {
        def explicitGradleVersions = System.getProperty('org.gradle.test.gradleVersions')
        if (explicitGradleVersions) {
            return Arrays.asList(explicitGradleVersions.split("\\|"))
        } else {
            [GradleVersion.current().version]
        }
    }
}

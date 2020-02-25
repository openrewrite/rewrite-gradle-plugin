package org.gradle.rewrite

import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

import java.lang.management.ManagementFactory

class AbstractRewritePluginTests extends Specification {
    static final List<String> GRADLE_VERSIONS_UNDER_TEST = gradleVersionsUnderTest()

    @Rule
    TemporaryFolder projectDir = new TemporaryFolder()

    File settingsFile

    File buildFile

    @SuppressWarnings("GroovyAssignabilityCheck")
    File writeSource(String source) {
        String packageName = (source =~ /package\s+([\w.]+)/)[0][1]
        String className = (source =~ /(class|interface)\s+(\w+)\s+/)[0][2]
        String sourceFilePackage = "src/main/java/${packageName.replace('.', '/')}"
        new File(projectDir.root, sourceFilePackage).mkdirs()
        def file = projectDir.newFile("$sourceFilePackage/${className}.java")
        file << source
        return file
    }

    GradleRunner gradleRunner(String gradleVersion) {
        GradleRunner.create()
                .withDebug(ManagementFactory.getRuntimeMXBean().getInputArguments().toString().indexOf("-agentlib:jdwp") > 0)
                .withProjectDir(projectDir.root)
                .withArguments('fixSourceLint', '-s')
                .withPluginClasspath()
                .forwardOutput()
                .tap {
                    gradleVersion == GradleVersion.current().toString() ? null : it.withGradleVersion(gradleVersion)
                }
    }

    static private List<String> gradleVersionsUnderTest() {
        def explicitGradleVersions = System.getProperty('org.gradle.test.gradleVersions')
        if (explicitGradleVersions) {
            return Arrays.asList(explicitGradleVersions.split("\\|"))
        } else {
            [GradleVersion.current().toString()]
        }
    }
}

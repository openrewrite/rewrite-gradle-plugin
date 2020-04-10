package org.gradle.rewrite

import org.gradle.internal.impldep.org.eclipse.jgit.api.Git
import spock.lang.Unroll

class RewriteCheckstylePluginTests extends AbstractRewritePluginTests {
    private static final String javaSourceWithCheckstyleViolation = """
            package acme;

            public class A {
                boolean ifNoElse() {
                    if (isOddMillis()) {
                        return true;
                    }
                    return false;
                }
                
                static boolean isOddMillis() {
                    boolean even = System.currentTimeMillis() % 2 == 0;
                    if (even == true) {
                        return false;
                    }
                    else {
                        return true;
                    }
                }
            }
        """.stripIndent()

    private static final String javaSourceFixed = """
            package acme;

            public class A {
                boolean ifNoElse() {
                    return isOddMillis();
                }
                
                static boolean isOddMillis() {
                    boolean even = System.currentTimeMillis() % 2 == 0;
                    return !even;
                }
            }
        """.stripIndent()

    def setup() {
        projectDir.newFolder('config', 'checkstyle')
        projectDir.newFile('config/checkstyle/checkstyle.xml') << """\
            <?xml version="1.0"?>
            <!DOCTYPE module PUBLIC
                    "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
                    "https://checkstyle.org/dtds/configuration_1_3.dtd">
            <module name="Checker">
                <module name="TreeWalker">
                    <module name="SimplifyBooleanExpression"/>
                    <module name="SimplifyBooleanReturn"/>
                </module>
            </module>
        """.stripIndent()

        projectDir.newFile('.gitignore') << """\
            build/
            .gradle/
        """.stripIndent()

        settingsFile = projectDir.newFile('settings.gradle')
        settingsFile << "rootProject.name = 'hello-world'"
        buildFile = projectDir.newFile('build.gradle')
        buildFile << """
            plugins {
                id 'java'
                id 'checkstyle'
                id 'org.gradle.rewrite-checkstyle'
            }
            
            repositories {
                mavenLocal()
                mavenCentral()
            }
        """

        projectDir.newFolder('src', 'main', 'java', 'acme')
    }

    @Unroll
    def "tasks available for each source set (gradle version #gradleVersion)"() {
        given:
        def tasks = ['tasks', '--all']

        when:
        def output = gradleRunner(gradleVersion as String, *tasks).build().output

        then:
        output.contains("rewriteCheckstyleMain")
        output.contains("rewriteCheckstyleTest")

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    @Unroll
    def "rebase fixup automatic fixes to checkstyle violations (gradle version #gradleVersion)"() {
        given:
        def sourceFile = writeSource(javaSourceWithCheckstyleViolation)
        def git = setupAutoCommit()

        buildFile << """
            rewrite {
                action = org.gradle.rewrite.RewriteAction.REBASE_FIXUP
            }
        """

        when:
        gradleRunner(gradleVersion as String, 'rewriteCheckstyleMain').build()

        then:
        sourceFile.text == javaSourceFixed
        git.log().call()[0].fullMessage == 'Add java source file'

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    @Unroll
    def "fix file that is already in git index re-adds it to the index but doesn't commit (gradle version #gradleVersion)"() {
        given:
        def sourceFile = writeSource(javaSourceWithCheckstyleViolation)
        def git = setupAutoCommit()

        sourceFile << '\n' // change the file

        git.add()
            .addFilepattern('src/main/java/acme/A.java')
            .call()

        when:
        gradleRunner(gradleVersion as String, 'rewriteCheckstyleMain').build()

        then:
        sourceFile.text == javaSourceFixed + '\n'
        git.status().call().getChanged().contains('src/main/java/acme/A.java')

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    @Unroll
    def "fix file that is modified but not in index does not add to the index or commit (gradle version #gradleVersion)"() {
        given:
        def sourceFile = writeSource(javaSourceWithCheckstyleViolation)
        def git = setupAutoCommit()

        when:
        sourceFile << '\n//test' // change the file

        then:
        git.status().call().getModified().contains('src/main/java/acme/A.java')

        when:
        gradleRunner(gradleVersion as String, 'rewriteCheckstyleMain').build()

        then:
        sourceFile.text == javaSourceFixed + '\n//test'
        git.status().call().getModified().contains('src/main/java/acme/A.java')

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    private Git setupAutoCommit() {
        def git = Git.init().setDirectory(projectDir.root).call()
        def config = git.getRepository().config

        config.setBoolean('commit', null, 'gpgsign', false)
        config.save()

        buildFile << """
            rewrite {
                action = org.gradle.rewrite.RewriteAction.COMMIT
            }
        """

        git.add()
                .addFilepattern('.gitignore')
                .addFilepattern("build.gradle")
                .addFilepattern("settings.gradle")
                .addFilepattern("config/checkstyle/checkstyle.xml")
                .call()

        git.commit()
                .setMessage('Initial commit')
                .setAuthor('bot', 'bot@gradle.com')
                .call()

        git.add()
                .addFilepattern('src/main/java/acme/A.java')
                .call()

        git.commit()
                .setMessage('Add java source file')
                .setAuthor('bot', 'bot@gradle.com')
                .call()

        return git
    }

    @Unroll
    def "fixes checkstyle issues in place (gradle version #gradleVersion)"() {
        given:
        def sourceFile = writeSource(javaSourceWithCheckstyleViolation)

        when:
        gradleRunner(gradleVersion as String, 'rewriteCheckstyleMain').buildAndFail()

        then:
        sourceFile.text == javaSourceFixed

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    @Unroll
    def "fixes checkstyle issues in place but 'ignore failures', i.e. don't fail the build (gradle version #gradleVersion)"() {
        given:
        def sourceFile = writeSource(javaSourceWithCheckstyleViolation)

        buildFile << """
            rewrite {
                ignoreFailures = true
            }
        """

        when:
        gradleRunner(gradleVersion as String, 'rewriteCheckstyleMain').build()

        then:
        sourceFile.text == javaSourceFixed

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    @Unroll
    def "just generate diff, don't fix in place (gradle version #gradleVersion)"() {
        given:
        def sourceFile = writeSource(javaSourceWithCheckstyleViolation)

        buildFile << """
            rewrite {
                action = org.gradle.rewrite.RewriteAction.WARN_ONLY
            }
        """

        when:
        gradleRunner(gradleVersion as String, 'rewriteCheckstyleMain').buildAndFail()

        then:
        sourceFile.text == javaSourceWithCheckstyleViolation

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    @Unroll
    def "issues fixed in place are not seen as violations on a subsequent run (gradle version #gradleVersion)"() {
        given:
        def sourceFile = writeSource(javaSourceWithCheckstyleViolation)
        writeSource("package acme;\nclass B {\n}")
        projectDir.newFile("src/main/java/C.txt") << "some text"

        when:
        gradleRunner(gradleVersion as String, 'rewriteCheckstyleMain').buildAndFail()

        then:
        sourceFile.text == javaSourceFixed

        then:
        gradleRunner(gradleVersion as String, 'rewriteCheckstyleMain').build()

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }

    @Unroll
    def "exclude checks by name (gradle version #gradleVersion)"() {
        given:
        def sourceFile = writeSource(javaSourceWithCheckstyleViolation)

        buildFile << """\
            rewrite {
                excludeChecks = ['SimplifyBooleanExpression', 'SimplifyBooleanReturn']
            }
        """.stripIndent()

        when:
        gradleRunner(gradleVersion as String, 'rewriteCheckstyleMain').build()

        then:
        sourceFile.text == javaSourceWithCheckstyleViolation

        then:
        gradleRunner(gradleVersion as String, 'rewriteCheckstyleMain').build()

        where:
        gradleVersion << GRADLE_VERSIONS_UNDER_TEST
    }
}

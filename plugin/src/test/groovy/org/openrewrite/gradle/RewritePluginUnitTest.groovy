package org.openrewrite.gradle

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

class RewritePluginUnitTest extends Specification {

    def "rewriteDiscover"() {
        ProjectBuilder.builder()
                .withProjectDir()
    }
}

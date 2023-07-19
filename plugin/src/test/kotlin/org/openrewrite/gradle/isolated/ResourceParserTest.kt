/*
 * Copyright ${year} the original author or authors.
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
package org.openrewrite.gradle.isolated

import org.assertj.core.api.Assertions
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openrewrite.gradle.DefaultRewriteExtension
import org.openrewrite.java.internal.JavaTypeCache
import java.io.File
import kotlin.io.path.Path

class ResourceParserTest {

    @Test
    fun `resource parser is excluding subprojects directories using the base dir`(
        @TempDir userHome: File
    ) {

        val path = Path("src/test/samples/resourceParserTest/root").toAbsolutePath()

        val project = ProjectBuilder.builder()
            .withProjectDir(path.resolve("project").toFile())
            .withGradleUserHomeDir(userHome)
            .build()

        ProjectBuilder.builder()
            .withProjectDir(path.resolve("project/subproject").toFile())
            .withParent(project)
            .build()

        val rewriteExtension = DefaultRewriteExtension(project)

        val resourceParser = ResourceParser(path, project, rewriteExtension, JavaTypeCache())

        val sources = resourceParser.listSources(project.projectDir.toPath())

        Assertions.assertThat(sources.size).isEqualTo(1)
    }
}

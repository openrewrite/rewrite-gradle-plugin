/*
 * Copyright 2026 the original author or authors.
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
package org.openrewrite.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.Issue;

import java.net.URI;
import java.util.Collection;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

@Issue("https://github.com/openrewrite/rewrite-gradle-plugin/issues/458")
class CodeGenomeProjectWarningTest {

    private static final Collection<URI> MAVEN_CENTRAL = singletonList(URI.create("https://repo.maven.apache.org/maven2/"));

    @ParameterizedTest
    @ValueSource(strings = {"latest.release", "latest.integration", "6.+", "+", "[6.0,7.0)"})
    void warnOnDynamicVersionsResolvedFromMavenCentral(String version) {
        String warning = CodeGenomeProjectWarning.warningFor(
          MAVEN_CENTRAL, singletonList("org.openrewrite.recipe:rewrite-spring:" + version));

        assertThat(warning)
          .contains("org.openrewrite.recipe:rewrite-spring:" + version)
          .contains(CodeGenomeProjectWarning.CREDENTIALS_DOCS);
    }

    @Test
    void warnOnModerneRecipeArtifacts() {
        assertThat(CodeGenomeProjectWarning.warningFor(
          MAVEN_CENTRAL, singletonList("io.moderne.recipe:rewrite-spring:latest.release")))
          .contains("io.moderne.recipe:rewrite-spring:latest.release");
    }

    @Test
    void noWarningOnPinnedVersions() {
        assertThat(CodeGenomeProjectWarning.warningFor(
          MAVEN_CENTRAL, singletonList("org.openrewrite.recipe:rewrite-spring:6.15.0")))
          .isNull();
    }

    @Test
    void noWarningOnUnrelatedArtifacts() {
        assertThat(CodeGenomeProjectWarning.warningFor(
          MAVEN_CENTRAL, singletonList("com.example:my-recipes:latest.release")))
          .isNull();
    }

    @Test
    void noWarningWithoutRecipeArtifacts() {
        assertThat(CodeGenomeProjectWarning.warningFor(MAVEN_CENTRAL, emptyList())).isNull();
    }

    @Test
    void noWarningBehindAnInternalMirror() {
        assertThat(CodeGenomeProjectWarning.warningFor(
          singletonList(URI.create("https://artifacts.internal.example.com/maven-central")),
          singletonList("org.openrewrite.recipe:rewrite-spring:latest.release")))
          .isNull();
    }

    @Test
    void noWarningWhenTheCodeGenomeProjectIsConfigured() {
        List<URI> repositories = asList(
          URI.create("https://artifacts.codegenomeproject.org/maven"),
          URI.create("https://repo.maven.apache.org/maven2/"));

        assertThat(CodeGenomeProjectWarning.warningFor(
          repositories, singletonList("org.openrewrite.recipe:rewrite-spring:latest.release")))
          .isNull();
    }

    @Test
    void noWarningWhenRepositoriesAreNotVisibleToThePlugin() {
        assertThat(CodeGenomeProjectWarning.warningFor(
          emptyList(), singletonList("org.openrewrite.recipe:rewrite-spring:latest.release")))
          .isNull();
    }
}

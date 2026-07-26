/*
 * Copyright 2025 the original author or authors.
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
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.Issue;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.gradle.DelegatingProjectParser.fingerprint;

@Issue("https://github.com/openrewrite/rewrite-gradle-plugin/issues/453")
class DelegatingProjectParserTest {

    @Test
    void unchangedClasspathHasEqualFingerprints(@TempDir Path tempDir) throws IOException {
        Path jar = write(tempDir.resolve("recipes.jar"), "first");

        assertThat(fingerprint(urls(jar))).isEqualTo(fingerprint(urls(jar)));
    }

    @Test
    void orderDoesNotAffectFingerprint(@TempDir Path tempDir) throws IOException {
        Path first = write(tempDir.resolve("first.jar"), "first");
        Path second = write(tempDir.resolve("second.jar"), "second");

        assertThat(fingerprint(urls(first, second))).isEqualTo(fingerprint(urls(second, first)));
    }

    @Test
    void replacedJarChangesFingerprint(@TempDir Path tempDir) throws IOException {
        Path jar = write(tempDir.resolve("recipes.jar"), "first");
        List<String> before = fingerprint(urls(jar));

        write(jar, "second");

        assertThat(fingerprint(urls(jar))).isNotEqualTo(before);
    }

    @Test
    void rebuiltJarOfEqualSizeChangesFingerprint(@TempDir Path tempDir) throws IOException {
        Path jar = write(tempDir.resolve("recipes.jar"), "first");
        List<String> before = fingerprint(urls(jar));

        touch(jar);

        assertThat(fingerprint(urls(jar))).isNotEqualTo(before);
    }

    @Test
    void changedFileWithinDirectoryChangesFingerprint(@TempDir Path tempDir) throws IOException {
        Path classes = Files.createDirectories(tempDir.resolve("classes/org/example"));
        write(classes.resolve("Recipe.class"), "first");
        List<String> before = fingerprint(urls(tempDir.resolve("classes")));

        touch(write(classes.resolve("Recipe.class"), "second"));

        assertThat(fingerprint(urls(tempDir.resolve("classes")))).isNotEqualTo(before);
    }

    @Test
    void fileAddedToDirectoryChangesFingerprint(@TempDir Path tempDir) throws IOException {
        Path classes = Files.createDirectories(tempDir.resolve("classes/org/example"));
        write(classes.resolve("Recipe.class"), "first");
        List<String> before = fingerprint(urls(tempDir.resolve("classes")));

        write(classes.resolve("OtherRecipe.class"), "first");

        assertThat(fingerprint(urls(tempDir.resolve("classes")))).isNotEqualTo(before);
    }

    private static Path write(Path file, String content) throws IOException {
        return Files.write(file, content.getBytes(UTF_8));
    }

    private static Path touch(Path file) throws IOException {
        FileTime lastModified = Files.getLastModifiedTime(file);
        return Files.setLastModifiedTime(file, FileTime.fromMillis(lastModified.toMillis() + 1_000));
    }

    private static List<URL> urls(Path... paths) {
        return Arrays.stream(paths)
                .map(path -> {
                    try {
                        return path.toUri().toURL();
                    } catch (MalformedURLException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .collect(toList());
    }
}

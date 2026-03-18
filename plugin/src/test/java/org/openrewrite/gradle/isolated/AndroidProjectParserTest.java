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
package org.openrewrite.gradle.isolated;

import org.gradle.api.Project;
import org.gradle.api.plugins.ExtensionContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.gradle.RewriteExtension;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for bugs in {@code AndroidProjectParser#findAndroidProjectVariants} (lines 207-225).
 * <p>
 * Bug 1 — {@code instanceof BaseAppModuleExtension} throws {@link NoClassDefFoundError}
 * on old AGP (pre-4.x) because the internal class doesn't exist on the classpath.
 * <p>
 * Bug 2 — AGP 9.x decorates app extensions ({@code ApplicationExtensionImpl$AgpDecorated_Decorated})
 * which don't pass {@code instanceof} checks, causing {@link UnsupportedOperationException}.
 * <p>
 * Bug 3 — Same as Bug 2 but for library modules ({@code LibraryExtensionImpl$AgpDecorated_Decorated}).
 * <p>
 * Fix: replaced {@code instanceof BaseAppModuleExtension}/{@code LibraryExtension} with
 * {@code instanceof TestedExtension} (stable public API parent) plus reflection-based
 * method detection for {@code getApplicationVariants}/{@code getLibraryVariants}.
 */
class AndroidProjectParserTest {

    /**
     * Bug 1 — On old AGP (pre-4.x), {@code BaseAppModuleExtension} doesn't exist.
     * Previously the {@code instanceof} check would throw {@link NoClassDefFoundError}.
     * Now we use {@code TestedExtension} (available since early AGP) so this is safe.
     */
    @Test
    void handlesExtensionWithoutBaseAppModuleExtensionOnClasspath(@TempDir Path tempDir) {
        // given - extension that is not a TestedExtension (simulates old AGP or unknown type)
        Object extension = new Object();
        Project project = createMockProject(tempDir, extension);
        AndroidProjectParser parser = createParser(tempDir, project);

        // when
        Collection<Path> dirs = parser.findSourceDirectories(project);

        // then - should return empty without throwing
        assertThat(dirs).isEmpty();
    }

    /**
     * Bug 2 — AGP 9.x decorates app extensions so they don't pass {@code instanceof}
     * checks for the old concrete types. Previously threw {@link UnsupportedOperationException}.
     */
    @Test
    void agp9DecoratedAppExtensionHandledGracefully(@TempDir Path tempDir) {
        // given - simulates an AGP 9.x decorated app extension
        Object decoratedAppExtension = new Object();
        Project project = createMockProject(tempDir, decoratedAppExtension);
        AndroidProjectParser parser = createParser(tempDir, project);

        // when
        Collection<Path> dirs = parser.findSourceDirectories(project);

        // then - should return empty without throwing
        assertThat(dirs).isEmpty();
    }

    /**
     * Bug 3 — Same as Bug 2 but for library modules.
     */
    @Test
    void agp9DecoratedLibraryExtensionHandledGracefully(@TempDir Path tempDir) {
        // given - simulates an AGP 9.x decorated library extension
        Object decoratedLibExtension = new Object();
        Project project = createMockProject(tempDir, decoratedLibExtension);
        AndroidProjectParser parser = createParser(tempDir, project);

        // when
        Collection<Path> dirs = parser.findSourceDirectories(project);

        // then - should return empty without throwing
        assertThat(dirs).isEmpty();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AndroidProjectParser createParser(Path tempDir, Project project) {
        RewriteExtension rewriteExtension = new RewriteExtension(project);
        return new AndroidProjectParser(tempDir, null, rewriteExtension, Collections.emptyList());
    }

    private Project createMockProject(Path tempDir, Object androidExtension) {
        ExtensionContainer extensions = (ExtensionContainer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{ExtensionContainer.class},
                (proxy, method, args) -> {
                    if ("findByName".equals(method.getName()) && args != null
                            && args.length == 1 && "android".equals(args[0])) {
                        return androidExtension;
                    }
                    return null;
                }
        );

        return (Project) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{Project.class},
                (proxy, method, args) -> {
                    if ("getExtensions".equals(method.getName())) {
                        return extensions;
                    }
                    if ("file".equals(method.getName()) && args != null && args.length >= 1) {
                        return new File(tempDir.toFile(), String.valueOf(args[0]));
                    }
                    if ("hasProperty".equals(method.getName())) {
                        return false;
                    }
                    return null;
                }
        );
    }
}

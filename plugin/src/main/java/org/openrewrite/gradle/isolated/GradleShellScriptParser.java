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
package org.openrewrite.gradle.isolated;

import org.openrewrite.PathUtils;
import org.openrewrite.text.PlainTextParser;

import java.nio.file.Path;
import java.nio.file.Paths;

public class GradleShellScriptParser extends PlainTextParser {
    private final Path baseDir;

    public GradleShellScriptParser(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public boolean accept(Path path) {
        Path relative = baseDir.relativize(path);
        return PathUtils.equalIgnoringSeparators(relative, Paths.get("gradlew"))
                || PathUtils.equalIgnoringSeparators(relative, Paths.get("gradlew.bat"));
    }
}

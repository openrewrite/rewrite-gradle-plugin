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

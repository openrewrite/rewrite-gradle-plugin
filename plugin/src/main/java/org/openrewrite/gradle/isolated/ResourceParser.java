/*
 * Copyright 2021 the original author or authors.
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
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.invocation.DefaultGradle;
import org.gradle.util.GradleVersion;
import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.gradle.GradleParser;
import org.openrewrite.gradle.RewriteExtension;
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.hcl.HclParser;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.json.JsonParser;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.protobuf.ProtoParser;
import org.openrewrite.python.PythonParser;
import org.openrewrite.quark.QuarkParser;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.text.PlainTextParser;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.yaml.YamlParser;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.openrewrite.PathUtils.separatorsToUnix;

public class ResourceParser {
    private static final Set<String> DEFAULT_IGNORED_DIRECTORIES = new HashSet<>(Arrays.asList("build", "target", "out", ".sonar", ".gradle", ".idea", ".project", "node_modules", ".git", ".metadata", ".DS_Store"));
    private static final Logger logger = Logging.getLogger(ResourceParser.class);
    private final Path baseDir;
    private final Project project;
    private final Collection<PathMatcher> exclusions;

    private final JavaTypeCache typeCache;

    private final Collection<PathMatcher> plainTextMasks;

    private final int sizeThresholdMb;


    public ResourceParser(Path baseDir, Project project, RewriteExtension extension, JavaTypeCache typeCache) {
        this.baseDir = baseDir;
        this.project = project;
        this.exclusions = pathMatchers(baseDir, mergeExclusions(project, baseDir, extension));
        this.plainTextMasks = pathMatchers(baseDir, extension.getPlainTextMasks());
        this.typeCache = typeCache;
        this.sizeThresholdMb = extension.getSizeThresholdMb();
    }

    private static Collection<String> mergeExclusions(Project project, Path baseDir, RewriteExtension extension) {
        return Stream.concat(
                project.getSubprojects().stream()
                        .map(subproject -> separatorsToUnix(baseDir.relativize(subproject.getProjectDir().toPath()).toString())),
                extension.getExclusions().stream()
        ).collect(toList());
    }

    private Collection<PathMatcher> pathMatchers(Path basePath, Collection<String> pathExpressions) {
        return pathExpressions.stream()
                .map(o -> basePath.getFileSystem().getPathMatcher("glob:" + o))
                .collect(Collectors.toList());
    }

    public Stream<SourceFile> parse(Path projectDir, Collection<Path> alreadyParsed, ExecutionContext ctx) {
        return parse(projectDir, alreadyParsed, emptyList(), emptyList(), ctx);
    }

    public Stream<SourceFile> parse(Path projectDir, Collection<Path> alreadyParsed, List<Path> classpath, List<NamedStyles> styles, ExecutionContext ctx) {
        Stream<SourceFile> sourceFiles;
        int countBefore = alreadyParsed.size();
        try {
            sourceFiles = parseSourceFiles(projectDir, alreadyParsed, classpath, styles, ctx);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }
        int count = alreadyParsed.size() - countBefore;
        if (count > 0) {
            logger.info("Scanned {} other sources in {}", count, projectDir);
        }
        return sourceFiles;
    }

    public List<Path> listSources(Path searchDir) throws IOException {
        JsonParser jsonParser = new JsonParser();
        XmlParser xmlParser = new XmlParser();
        YamlParser yamlParser = new YamlParser();
        PropertiesParser propertiesParser = new PropertiesParser();
        ProtoParser protoParser = new ProtoParser();
        PythonParser pythonParser = PythonParser.builder().build();
        HclParser hclParser = HclParser.builder().build();
        GroovyParser.Builder gpb = GroovyParser.builder()
                .classpath(emptyList());
        GroovyParser groovyParser = gpb.build();
        GradleParser gradleParser = GradleParser.builder()
                .groovyParser(gpb)
                .build();

        List<Path> resources = new ArrayList<>();
        Files.walkFileTree(searchDir, Collections.emptySet(), 16, new SimpleFileVisitor<Path>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isExcluded(dir) || isIgnoredDirectory(searchDir, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() != 0 && !attrs.isOther() && !isExcluded(file) && !isOverSizeThreshold(attrs.size())) {
                    if (jsonParser.accept(file) ||
                            xmlParser.accept(file) ||
                            yamlParser.accept(file) ||
                            propertiesParser.accept(file) ||
                            protoParser.accept(file) ||
                            hclParser.accept(file) ||
                            pythonParser.accept(file) ||
                            groovyParser.accept(file) ||
                            gradleParser.accept(file) ||
                            isParsedAsPlainText(file)
                    ) {
                        resources.add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return resources;
    }

    @SuppressWarnings({"DuplicatedCode"})
    public Stream<SourceFile> parseSourceFiles(Path searchDir, Collection<Path> alreadyParsed,
                                               List<Path> classpath, List<NamedStyles> styles, ExecutionContext ctx) throws IOException {

        List<Path> resources = new ArrayList<>();
        List<Path> quarkPaths = new ArrayList<>();
        List<Path> plainTextPaths = new ArrayList<>();

        Files.walkFileTree(searchDir, Collections.emptySet(), 16, new SimpleFileVisitor<Path>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isExcluded(dir) || isIgnoredDirectory(searchDir, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isOther() && !attrs.isSymbolicLink() &&
                        !alreadyParsed.contains(file) && !isExcluded(file)) {
                    if (isOverSizeThreshold(attrs.size())) {
                        logger.info("Parsing as quark {} as its size {}Mb exceeds size threshold {}Mb", file, attrs.size() / (1024L * 1024L), sizeThresholdMb);
                        quarkPaths.add(file);
                    } else if (isParsedAsPlainText(file)) {
                        plainTextPaths.add(file);
                    } else {
                        resources.add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        JsonParser jsonParser = new JsonParser();
        List<Path> jsonPaths = new ArrayList<>();

        XmlParser xmlParser = new XmlParser();
        List<Path> xmlPaths = new ArrayList<>();

        YamlParser yamlParser = new YamlParser();
        List<Path> yamlPaths = new ArrayList<>();

        PropertiesParser propertiesParser = new PropertiesParser();
        List<Path> propertiesPaths = new ArrayList<>();

        ProtoParser protoParser = new ProtoParser();
        List<Path> protoPaths = new ArrayList<>();

        HclParser hclParser = HclParser.builder().build();
        List<Path> hclPaths = new ArrayList<>();

        PythonParser pythonParser = PythonParser.builder()
                .typeCache(typeCache)
                .build();
        List<Path> pythonPaths = new ArrayList<>();

        GroovyParser groovyParser = GroovyParser.builder()
                .classpath(classpath)
                .typeCache(typeCache)
                .styles(styles)
                .logCompilationWarningsAndErrors(false)
                .build();
        List<Path> groovyPaths = new ArrayList<>();

        List<Path> buildscriptClasspath = project.getBuildscript().getConfigurations().getByName("classpath").resolve()
                .stream()
                .map(File::toPath)
                .collect(toList());

        List<Path> settingsClasspath;
        if (GradleVersion.current().compareTo(GradleVersion.version("4.4")) >= 0) {
            try {
                Settings settings = ((DefaultGradle) project.getGradle()).getSettings();
                settingsClasspath = settings.getBuildscript().getConfigurations().getByName("classpath").resolve()
                        .stream()
                        .map(File::toPath)
                        .collect(toList());
            } catch (IllegalStateException e) {
                settingsClasspath = emptyList();
            }
        } else {
            settingsClasspath = emptyList();
        }

        GradleParser gradleParser = GradleParser.builder()
                .groovyParser(GroovyParser.builder()
                        .typeCache(typeCache)
                        .styles(styles)
                        .logCompilationWarningsAndErrors(false))
                .buildscriptClasspath(buildscriptClasspath)
                .settingsClasspath(settingsClasspath)
                .build();
        List<Path> gradlePaths = new ArrayList<>();

        PlainTextParser plainTextParser = new PlainTextParser();

        QuarkParser quarkParser = new QuarkParser();

        resources.forEach(path -> {
            if (jsonParser.accept(path)) {
                jsonPaths.add(path);
            } else if (xmlParser.accept(path)) {
                xmlPaths.add(path);
            } else if (yamlParser.accept(path)) {
                yamlPaths.add(path);
            } else if (propertiesParser.accept(path)) {
                propertiesPaths.add(path);
            } else if (protoParser.accept(path)) {
                protoPaths.add(path);
            } else if (hclParser.accept(path)) {
                hclPaths.add(path);
            } else if (pythonParser.accept(path)) {
                pythonPaths.add(path);
            } else if (groovyParser.accept(path)) {
                groovyPaths.add(path);
            } else if (gradleParser.accept(path)) {
                gradlePaths.add(path);
            } else if (quarkParser.accept(path)) {
                quarkPaths.add(path);
            }
        });

        Stream<SourceFile> sourceFiles = Stream.empty();

        if (!jsonPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, jsonParser.parse(jsonPaths, baseDir, ctx));
            alreadyParsed.addAll(jsonPaths);
        }

        if (!xmlPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, xmlParser.parse(xmlPaths, baseDir, ctx));
            alreadyParsed.addAll(xmlPaths);
        }

        if (!yamlPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, yamlParser.parse(yamlPaths, baseDir, ctx));
            alreadyParsed.addAll(yamlPaths);
        }

        if (!propertiesPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, propertiesParser.parse(propertiesPaths, baseDir, ctx));
            alreadyParsed.addAll(propertiesPaths);
        }

        if (!protoPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, protoParser.parse(protoPaths, baseDir, ctx));
            alreadyParsed.addAll(protoPaths);
        }

        if (!hclPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, hclParser.parse(hclPaths, baseDir, ctx));
            alreadyParsed.addAll(hclPaths);
        }

        if (!pythonPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, pythonParser.parse(pythonPaths, baseDir, ctx));
            alreadyParsed.addAll(pythonPaths);
        }

        if (!groovyPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, groovyParser.parse(groovyPaths, baseDir, ctx));
            alreadyParsed.addAll(groovyPaths);
        }

        if (!gradlePaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, gradleParser.parse(gradlePaths, baseDir, ctx));
            alreadyParsed.addAll(gradlePaths);
        }

        if (!plainTextPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, plainTextParser.parse(plainTextPaths, baseDir, ctx));
            alreadyParsed.addAll(plainTextPaths);
        }

        if (!quarkPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, quarkParser.parse(quarkPaths, baseDir, ctx));
            alreadyParsed.addAll(quarkPaths);
        }

        // Filter out `null` values as safeguard against ill-behaved parsers; see https://github.com/openrewrite/rewrite/pull/3322
        return sourceFiles.filter(Objects::nonNull);
    }

    private boolean isOverSizeThreshold(long fileSize) {
        return sizeThresholdMb > 0 && fileSize > sizeThresholdMb * 1024L * 1024L;
    }

    private boolean isExcluded(Path path) {
        if (!exclusions.isEmpty()) {
            Path relative = baseDir.relativize(path);
            for (PathMatcher excluded : exclusions) {
                if (excluded.matches(relative)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isParsedAsPlainText(Path path) {
        if (!plainTextMasks.isEmpty()) {
            Path computed = baseDir.relativize(path);
            for (PathMatcher matcher : plainTextMasks) {
                if (matcher.matches(computed)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isIgnoredDirectory(Path searchDir, Path path) {
        for (Path pathSegment : searchDir.relativize(path)) {
            if (DEFAULT_IGNORED_DIRECTORIES.contains(pathSegment.toString())) {
                return true;
            }
        }
        return false;
    }
}

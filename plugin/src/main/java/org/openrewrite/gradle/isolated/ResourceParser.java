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
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.gradle.RewriteExtension;
import org.openrewrite.hcl.HclParser;
import org.openrewrite.json.JsonParser;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.protobuf.ProtoParser;
import org.openrewrite.quark.QuarkParser;
import org.openrewrite.text.PlainTextParser;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.yaml.YamlParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static org.openrewrite.gradle.TimeUtils.prettyPrint;

public class ResourceParser {
    private static final Set<String> DEFAULT_IGNORED_DIRECTORIES = new HashSet<>(Arrays.asList("build", "target", "out", ".gradle", ".idea", ".project", "node_modules", ".git", ".metadata", ".DS_Store"));
    private static final Logger logger = Logging.getLogger(ResourceParser.class);
    private final Path baseDir;
    private final Collection<PathMatcher> exclusions;
    private final int sizeThresholdMb;

    public ResourceParser(Path baseDir, Project project, RewriteExtension extension) {
        this.baseDir = baseDir;
        this.exclusions = exclusionMatchers(baseDir, mergeExclusions(project, extension));
        this.sizeThresholdMb = extension.getSizeThresholdMb();
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> mergeExclusions(Project project, RewriteExtension extension) {
        return Stream.concat(
                project.getSubprojects().stream()
                        .map(subproject -> project.getProjectDir().toPath().relativize(subproject.getProjectDir().toPath()) + "/**"),
                extension.getExclusions().stream()
        ).collect(toList());
    }

    private Collection<PathMatcher> exclusionMatchers(Path baseDir, Collection<String> exclusions) {
        return exclusions.stream()
                .map(o -> baseDir.getFileSystem().getPathMatcher("glob:" + o))
                .collect(Collectors.toList());
    }

    public List<SourceFile> parse(Path projectDir, Collection<Path> alreadyParsed, ExecutionContext ctx) {
        List<SourceFile> sourceFiles;
        logger.info("Parsing other sources from {}", projectDir);
        Instant start = Instant.now();
        try {
            sourceFiles = new ArrayList<>(parseSourceFiles(projectDir, alreadyParsed, ctx));
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }
        if (sourceFiles.size() > 0) {
            Duration duration = Duration.between(start, Instant.now());
            logger.info("Finished parsing {} other sources from {} in {} ({} per source)",
                    sourceFiles.size(), projectDir, prettyPrint(duration), prettyPrint(duration.dividedBy(sourceFiles.size())));
        }
        return sourceFiles;
    }

    public List<Path> listSources(Path searchDir) throws IOException {
        GradleShellScriptParser gradleShellScriptParser = new GradleShellScriptParser(baseDir);
        JsonParser jsonParser = new JsonParser();
        XmlParser xmlParser = new XmlParser();
        YamlParser yamlParser = new YamlParser();
        PropertiesParser propertiesParser = new PropertiesParser();
        ProtoParser protoParser = new ProtoParser();
        HclParser hclParser = HclParser.builder().build();

        List<Path> resources = new ArrayList<>();
        Files.walkFileTree(searchDir, Collections.emptySet(), 16, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isExcluded(dir) || isIgnoredDirectory(searchDir, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() != 0 && !attrs.isOther() && !isExcluded(file) && !isOverSizeThreshold(attrs.size())) {
                    if (gradleShellScriptParser.accept(file) ||
                            jsonParser.accept(file) ||
                            xmlParser.accept(file) ||
                            yamlParser.accept(file) ||
                            propertiesParser.accept(file) ||
                            protoParser.accept(file) ||
                            hclParser.accept(file)) {
                        resources.add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return resources;
    }

    @SuppressWarnings({"DuplicatedCode", "unchecked"})
    public <S extends SourceFile> List<S> parseSourceFiles(
            Path searchDir,
            Collection<Path> alreadyParsed,
            ExecutionContext ctx) throws IOException {

        List<Path> resources = new ArrayList<>();
        List<Path> quarkPaths = new ArrayList<>();
        List<Path> plainTextPaths = new ArrayList<>();
        Files.walkFileTree(searchDir, Collections.emptySet(), 16, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isExcluded(dir) || isIgnoredDirectory(searchDir, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isOther() && !attrs.isSymbolicLink() &&
                        !alreadyParsed.contains(file) && !isExcluded(file)) {
                    if (isOverSizeThreshold(attrs.size())) {
                        logger.info("Parsing as quark " + file + " as its size + " + attrs.size() / (1024L * 1024L) +
                                "Mb exceeds size threshold " + sizeThresholdMb + "Mb");
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

        List<S> sourceFiles = new ArrayList<>(resources.size());

        GradleShellScriptParser gradleShellScriptParser = new GradleShellScriptParser(baseDir);
        List<Path> gradleShellScriptPaths = new ArrayList<>();

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

        PlainTextParser plainTextParser = new PlainTextParser();

        QuarkParser quarkParser = new QuarkParser();

        resources.forEach(path -> {
            if (gradleShellScriptParser.accept(path)) {
                gradleShellScriptPaths.add(path);
            } else if (jsonParser.accept(path)) {
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
            } else if (quarkParser.accept(path)) {
                quarkPaths.add(path);
            }
        });

        sourceFiles.addAll((List<S>) gradleShellScriptParser.parse(gradleShellScriptPaths, baseDir, ctx));
        alreadyParsed.addAll(gradleShellScriptPaths);

        sourceFiles.addAll((List<S>) jsonParser.parse(jsonPaths, baseDir, ctx));
        alreadyParsed.addAll(jsonPaths);

        sourceFiles.addAll((List<S>) xmlParser.parse(xmlPaths, baseDir, ctx));
        alreadyParsed.addAll(xmlPaths);

        sourceFiles.addAll((List<S>) yamlParser.parse(yamlPaths, baseDir, ctx));
        alreadyParsed.addAll(yamlPaths);

        sourceFiles.addAll((List<S>) propertiesParser.parse(propertiesPaths, baseDir, ctx));
        alreadyParsed.addAll(propertiesPaths);

        sourceFiles.addAll((List<S>) protoParser.parse(protoPaths, baseDir, ctx));
        alreadyParsed.addAll(protoPaths);

        sourceFiles.addAll((List<S>) hclParser.parse(hclPaths, baseDir, ctx));
        alreadyParsed.addAll(hclPaths);

        sourceFiles.addAll((List<S>) plainTextParser.parse(plainTextPaths, baseDir, ctx));
        alreadyParsed.addAll(plainTextPaths);

        sourceFiles.addAll((List<S>) quarkParser.parse(quarkPaths, baseDir, ctx));
        alreadyParsed.addAll(quarkPaths);

        return sourceFiles;
    }

    private boolean isOverSizeThreshold(long fileSize) {
        return (sizeThresholdMb > 0 && fileSize > sizeThresholdMb * 1024L * 1024L);
    }

    private boolean isExcluded(Path path) {
        for (PathMatcher excluded : exclusions) {
            if (excluded.matches(baseDir.relativize(path))) {
                return true;
            }
        }
        return false;
    }

    private boolean isParsedAsPlainText(Path path) {
        String pathString = path.toString();

        return  pathString.contains("/META-INF/services") ||
                pathString.endsWith(".gitignore")||
                pathString.endsWith(".gitattributes")||
                pathString.endsWith(".java-version")||
                pathString.endsWith(".sdkmanrc");
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

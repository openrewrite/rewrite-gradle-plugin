/*
 * Copyright 2024 the original author or authors.
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

import com.android.build.gradle.BaseExtension;
import com.android.build.gradle.LibraryExtension;
import com.android.build.gradle.api.BaseVariant;
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension;
import org.gradle.api.DomainObjectSet;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.gradle.RewriteExtension;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.polyglot.OmniParser;
import org.openrewrite.polyglot.ProgressBar;
import org.openrewrite.polyglot.SourceFileStream;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.tree.ParsingExecutionContextView;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class AndroidProjectParser {
    private static final Logger logger = Logging.getLogger(DefaultProjectParser.class);
    private final Path baseDir;
    private final RewriteExtension rewriteExtension;
    private final List<NamedStyles> styles;

    AndroidProjectParser(Path baseDir, RewriteExtension rewriteExtension, List<NamedStyles> styles) {
        this.baseDir = baseDir;
        this.rewriteExtension = rewriteExtension;
        this.styles = styles;
    }

    SourceFileStream parseProjectSourceSets(Project project,
                                            ProgressBar progressBar,
                                            Path buildDir,
                                            Charset sourceCharset,
                                            Set<Path> alreadyParsed,
                                            Collection<PathMatcher> exclusions,
                                            ExecutionContext ctx,
                                            OmniParser omniParser) {
        SourceFileStream sourceFileStream = SourceFileStream.build(
                project.getPath(),
                projectName -> progressBar.intermediateResult(":" + projectName));

        for (AndroidProjectVariant variant : findAndroidProjectVariants(project)) {
            JavaVersion javaVersion = getJavaVersion(project);
            final Charset javaSourceCharset = getSourceFileEncoding(project, sourceCharset);

            for (String sourceSetName : variant.getSourceSetNames()) {
                Stream<SourceFile> sourceSetSourceFiles = Stream.of();
                int sourceSetSize = 0;

                Set<Path> javaAndKotlinDirectories = new HashSet<>();
                javaAndKotlinDirectories.addAll(variant.getJavaDirectories(sourceSetName));
                javaAndKotlinDirectories.addAll(variant.getKotlinDirectories(sourceSetName));

                List<Path> javaAndKotlinPaths = javaAndKotlinDirectories.stream()
                        .filter(Files::exists)
                        .filter(dir -> !alreadyParsed.contains(dir))
                        .flatMap(dir -> {
                            try {
                                return Files.walk(dir);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        })
                        .filter(Files::isRegularFile)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .distinct()
                        .collect(Collectors.toList());

                List<Path> javaPaths = javaAndKotlinPaths.stream()
                        .filter(path -> path.toString().endsWith(".java"))
                        .collect(Collectors.toList());
                List<Path> kotlinPaths = javaAndKotlinPaths.stream()
                        .filter(path -> path.toString().endsWith(".kt"))
                        .collect(Collectors.toList());

                JavaTypeCache javaTypeCache = new JavaTypeCache();

                // The compilation classpath doesn't include the transitive dependencies
                // The runtime classpath doesn't include compile only dependencies, e.g.: lombok, servlet-api
                // So we use both together to get comprehensive type information.
                Set<Path> dependencyPaths = new HashSet<>();
                try {
                    Stream.concat(variant.getCompileClasspath().stream(), variant.getRuntimeClasspath().stream())
                            .map(Path::toAbsolutePath)
                            .map(Path::normalize)
                            .forEach(dependencyPaths::add);
                } catch (Exception e) {
                    logger.warn("Unable to resolve classpath for variant {} sourceSet {}:{}",
                            variant.getName(),
                            project.getPath(),
                            sourceSetName,
                            e);
                }

                if (!javaPaths.isEmpty()) {
                    alreadyParsed.addAll(javaPaths);
                    Stream<SourceFile> parsedJavaFiles = parseJavaFiles(javaPaths,
                            ctx,
                            buildDir,
                            exclusions,
                            javaSourceCharset,
                            javaVersion,
                            dependencyPaths,
                            javaTypeCache);
                    sourceSetSourceFiles = Stream.concat(sourceSetSourceFiles, parsedJavaFiles);
                    sourceSetSize += javaPaths.size();

                    logger.info("Scanned {} Java sources in {}/{}", javaPaths.size(), project.getPath(), sourceSetName);
                }

                if (!kotlinPaths.isEmpty()) {
                    alreadyParsed.addAll(kotlinPaths);
                    Stream<SourceFile> parsedKotlinFiles = parseKotlinFiles(kotlinPaths,
                            ctx,
                            buildDir,
                            exclusions,
                            javaSourceCharset,
                            javaVersion,
                            dependencyPaths,
                            javaTypeCache);
                    sourceSetSourceFiles = Stream.concat(sourceSetSourceFiles, parsedKotlinFiles);
                    sourceSetSize += kotlinPaths.size();
                    logger.info("Scanned {} Kotlin sources in {}/{}",
                            kotlinPaths.size(),
                            project.getPath(),
                            sourceSetName);
                }

                for (Path resourcesDir : variant.getResourcesDirectories(sourceSetName)) {
                    if (Files.exists(resourcesDir) && !alreadyParsed.contains(resourcesDir)) {
                        List<Path> accepted = omniParser.acceptedPaths(baseDir, resourcesDir);
                        sourceSetSourceFiles = Stream.concat(sourceSetSourceFiles,
                                omniParser.parse(accepted, baseDir, new InMemoryExecutionContext())
                                        .map(it -> it.withMarkers(it.getMarkers().add(javaVersion))));
                        alreadyParsed.addAll(accepted);
                        sourceSetSize += accepted.size();
                    }
                }

                JavaSourceSet sourceSetProvenance = JavaSourceSet.build(sourceSetName, dependencyPaths);
                sourceFileStream = sourceFileStream.concat(sourceSetSourceFiles.map(DefaultProjectParser.addProvenance(
                                sourceSetProvenance)),
                        sourceSetSize);
            }
        }
        return sourceFileStream;
    }

    Collection<Path> findSourceDirectories(Project project) {
        Set<Path> sourceDirectories = new HashSet<>();
        for (AndroidProjectVariant variant : findAndroidProjectVariants(project)) {
            for (String sourceSetName : variant.getSourceSetNames()) {
                sourceDirectories.addAll(variant.getJavaDirectories(sourceSetName));
                sourceDirectories.addAll(variant.getKotlinDirectories(sourceSetName));
                sourceDirectories.addAll(variant.getResourcesDirectories(sourceSetName));
            }
        }
        return sourceDirectories;
    }

    private List<AndroidProjectVariant> findAndroidProjectVariants(Project project) {
        List<AndroidProjectVariant> variants = new ArrayList<>();
        Object extension = project.getExtensions().findByName("android");
        if (extension instanceof BaseAppModuleExtension) {
            BaseAppModuleExtension appExtension = (BaseAppModuleExtension) extension;
            addProjectVariant(variants, appExtension.getApplicationVariants());
            addProjectVariant(variants, appExtension.getTestVariants());
            addProjectVariant(variants, appExtension.getUnitTestVariants());
        } else if (extension instanceof LibraryExtension) {
            LibraryExtension libraryExtension = (LibraryExtension) extension;
            addProjectVariant(variants, libraryExtension.getLibraryVariants());
            addProjectVariant(variants, libraryExtension.getTestVariants());
            addProjectVariant(variants, libraryExtension.getUnitTestVariants());
        } else if (extension != null) {
            throw new UnsupportedOperationException("Unhandled android extension type: " + extension.getClass());
        }

        return variants;
    }

    private void addProjectVariant(List<AndroidProjectVariant> projectVariants,
                                   DomainObjectSet<? extends BaseVariant> variantSet) {
        variantSet.stream().map(AndroidProjectVariant::fromBaseVariant).forEach(projectVariants::add);
    }

    private JavaVersion getJavaVersion(Project project) {
        String sourceCompatibility = "";
        String targetCompatibility = "";

        Object extension = project.getExtensions().findByName("android");
        if (extension instanceof BaseExtension) {
            try {
                BaseExtension baseExtension = (BaseExtension) extension;
                AndroidProjectCompileOptions compileOptions = AndroidProjectCompileOptions.fromBaseExtension(
                        baseExtension);
                sourceCompatibility = compileOptions.getSourceCompatibility();
                targetCompatibility = compileOptions.getTargetCompatibility();
            } catch (Exception e) {
                e.printStackTrace();
                logger.warn("Unable to determine Java source or target compatibility versions: {}", e.getMessage());
            }
        }
        return new JavaVersion(Tree.randomId(),
                System.getProperty("java.runtime.version"),
                System.getProperty("java.vm.vendor"),
                sourceCompatibility,
                targetCompatibility);
    }

    Charset getSourceFileEncoding(Project project, Charset defaultCharset) {
        Object extension = project.getExtensions().findByName("android");
        if (extension instanceof BaseExtension) {
            try {
                BaseExtension baseExtension = (BaseExtension) extension;
                AndroidProjectCompileOptions compileOptions = AndroidProjectCompileOptions.fromBaseExtension(
                        baseExtension);
                return compileOptions.getEncoding();
            } catch (Exception e) {
                logger.warn("Unable to determine Java source file encoding: {}", e.getMessage());
            }
        }
        return defaultCharset;
    }

    private Stream<SourceFile> parseJavaFiles(List<Path> javaPaths,
                                              ExecutionContext ctx,
                                              Path buildDir,
                                              Collection<PathMatcher> exclusions,
                                              Charset javaSourceCharset,
                                              JavaVersion javaVersion,
                                              Set<Path> dependencyPaths,
                                              JavaTypeCache javaTypeCache) {
        ParsingExecutionContextView.view(ctx).setCharset(javaSourceCharset);

        return Stream.of((Supplier<JavaParser>) () -> JavaParser.fromJavaVersion()
                .classpath(dependencyPaths)
                .styles(styles)
                .typeCache(javaTypeCache)
                .logCompilationWarningsAndErrors(rewriteExtension.getLogCompilationWarningsAndErrors())
                .build()).map(Supplier::get).flatMap(jp -> jp.parse(javaPaths, baseDir, ctx)).map(cu -> {
            if (DefaultProjectParser.isExcluded(exclusions, cu.getSourcePath()) || cu.getSourcePath()
                    .startsWith(buildDir)) {
                return null;
            }
            return cu;
        }).filter(Objects::nonNull).map(it -> it.withMarkers(it.getMarkers().add(javaVersion)));
    }

    private Stream<SourceFile> parseKotlinFiles(List<Path> kotlinPaths,
                                                ExecutionContext ctx,
                                                Path buildDir,
                                                Collection<PathMatcher> exclusions,
                                                Charset javaSourceCharset,
                                                JavaVersion javaVersion,
                                                Set<Path> dependencyPaths,
                                                JavaTypeCache javaTypeCache) {
        ParsingExecutionContextView.view(ctx).setCharset(javaSourceCharset);

        return Stream.of((Supplier<KotlinParser>) () -> KotlinParser.builder()
                .classpath(dependencyPaths)
                .styles(styles)
                .typeCache(javaTypeCache)
                .logCompilationWarningsAndErrors(rewriteExtension.getLogCompilationWarningsAndErrors())
                .build()).map(Supplier::get).flatMap(kp -> kp.parse(kotlinPaths, baseDir, ctx)).map(cu -> {
            if (DefaultProjectParser.isExcluded(exclusions, cu.getSourcePath()) || cu.getSourcePath()
                    .startsWith(buildDir)) {
                return null;
            }
            return cu;
        }).filter(Objects::nonNull).map(it -> it.withMarkers(it.getMarkers().add(javaVersion)));
    }
}

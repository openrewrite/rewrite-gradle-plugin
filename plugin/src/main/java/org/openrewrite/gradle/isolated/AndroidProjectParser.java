package org.openrewrite.gradle.isolated;

import com.android.build.api.dsl.CompileOptions;
import com.android.build.gradle.BaseExtension;
import com.android.build.gradle.LibraryExtension;
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension;
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
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;
import org.openrewrite.polyglot.OmniParser;
import org.openrewrite.polyglot.SourceFileStream;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.tree.ParsingExecutionContextView;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
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
                                            Path buildDir,
                                            Charset sourceCharset,
                                            Set<Path> alreadyParsed,
                                            Collection<PathMatcher> exclusions,
                                            ExecutionContext ctx,
                                            OmniParser omniParser) {
        // FIXME: Does this affect progress bars?
        SourceFileStream sourceFileStream = SourceFileStream.build("", s -> {
        });

        for (AndroidProjectVariant variant : findAndroidProjectVariants(project)) {
            JavaVersion javaVersion = getJavaVersion(project);
            final Charset javaSourceCharset = getSourceFileEncoding(project, sourceCharset);
            // FIXME: source sets need to be ordered like is done with gradle
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

                List<Path> dependencyPathsNonFinal;

                try {
                    dependencyPathsNonFinal = Stream.concat(
                                    variant.getCompileClasspath().stream(),
                                    variant.getRuntimeClasspath().stream())
                            .map(Path::toAbsolutePath)
                            .map(Path::normalize)
                            .distinct()
                            .collect(Collectors.toList());
                } catch (Exception e) {
                    logger.warn(
                            "Unable to resolve classpath for variant {} sourceSet {}:{}",
                            variant.getName(),
                            project.getPath(),
                            sourceSetName,
                            e);
                    dependencyPathsNonFinal = Collections.emptyList();
                }
                List<Path> dependencyPaths = dependencyPathsNonFinal;

                if (!javaPaths.isEmpty()) {
                    alreadyParsed.addAll(javaPaths);
                    Stream<SourceFile> parsedJavaFiles = parseJavaFiles(
                            javaPaths,
                            ctx,
                            rewriteExtension,
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
                    Stream<SourceFile> parsedKotlinFiles = parseKotlinFiles(
                            kotlinPaths,
                            ctx,
                            rewriteExtension,
                            buildDir,
                            exclusions,
                            javaSourceCharset,
                            javaVersion,
                            dependencyPaths,
                            javaTypeCache);
                    sourceSetSourceFiles = Stream.concat(sourceSetSourceFiles, parsedKotlinFiles);
                    sourceSetSize += kotlinPaths.size();
                    logger.info(
                            "Scanned {} Kotlin sources in {}/{}",
                            kotlinPaths.size(),
                            project.getPath(),
                            sourceSetName);
                }

                // TODO: Groovy??

                for (Path resourcesDir : variant.getResourcesDirectories(sourceSetName)) {
                    if (Files.exists(resourcesDir) && !alreadyParsed.contains(resourcesDir)) {
                        List<Path> accepted = omniParser.acceptedPaths(baseDir, resourcesDir);
                        sourceSetSourceFiles = Stream.concat(
                                sourceSetSourceFiles,
                                omniParser.parse(accepted, baseDir, new InMemoryExecutionContext())
                                        .map(it -> it.withMarkers(it.getMarkers().add(javaVersion))));
                        alreadyParsed.addAll(accepted);
                        sourceSetSize += accepted.size();
                    }
                }

                JavaSourceSet sourceSetProvenance = JavaSourceSet.build(sourceSetName, dependencyPaths);
                sourceFileStream = sourceFileStream.concat(
                        sourceSetSourceFiles.map(addProvenance(sourceSetProvenance)),
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
            appExtension.getApplicationVariants()
                    .forEach(variant -> variants.add(AndroidProjectVariant.fromBaseVariant(variant)));
        } else if (extension instanceof LibraryExtension) {
            LibraryExtension libraryExtension = (LibraryExtension) extension;
            libraryExtension.getLibraryVariants()
                    .forEach(variant -> variants.add(AndroidProjectVariant.fromBaseVariant(variant)));
        } else if (extension != null) {
            throw new UnsupportedOperationException("Unhandled android extension type: " + extension.getClass());
        }

        return variants;
    }

    private JavaVersion getJavaVersion(Project project) {
        String sourceCompatibility = "";
        String targetCompatibility = "";

        Object extension = project.getExtensions().findByName("android");
        if (extension != null) {
            try {
                Class<?> extensionCl = extension.getClass();
                Method method = extensionCl.getMethod("getCompileOptions");
                CompileOptions compileOptions = (CompileOptions) method.invoke(extensionCl);
                sourceCompatibility = compileOptions.getSourceCompatibility().toString();
                targetCompatibility = compileOptions.getTargetCompatibility().toString();
            } catch (Exception e) {
                logger.warn("Unable to determine Java source or target compatibility versions: {}", e.getMessage());
            }
        }
        return new JavaVersion(
                Tree.randomId(),
                System.getProperty("java.runtime.version"),
                System.getProperty("java.vm.vendor"),
                sourceCompatibility,
                targetCompatibility);
    }

    Charset getSourceFileEncoding(Project project, Charset defaultCharset) {
        Object extension = project.getExtensions().findByName("android");
        if (extension instanceof BaseExtension) {
            BaseExtension baseExtension = (BaseExtension) extension;
            CompileOptions compileOptions = baseExtension.getCompileOptions();
            return Charset.forName(compileOptions.getEncoding());
        }
        return defaultCharset;
    }

    private Stream<SourceFile> parseJavaFiles(List<Path> javaPaths,
                                              ExecutionContext ctx,
                                              RewriteExtension extension,
                                              Path buildDir,
                                              Collection<PathMatcher> exclusions,
                                              Charset javaSourceCharset,
                                              JavaVersion javaVersion,
                                              List<Path> dependencyPaths,
                                              JavaTypeCache javaTypeCache) {
        ParsingExecutionContextView.view(ctx).setCharset(javaSourceCharset);

        return Stream.of((Supplier<JavaParser>) () -> JavaParser.fromJavaVersion()
                .classpath(dependencyPaths)
                .styles(styles)
                .typeCache(javaTypeCache)
                .logCompilationWarningsAndErrors(extension.getLogCompilationWarningsAndErrors())
                .build()).map(Supplier::get).flatMap(jp -> jp.parse(javaPaths, baseDir, ctx)).map(cu -> {
            if (isExcluded(exclusions, cu.getSourcePath()) || cu.getSourcePath().startsWith(buildDir)) {
                return null;
            }
            return cu;
        }).filter(Objects::nonNull).map(it -> it.withMarkers(it.getMarkers().add(javaVersion)));
    }

    private Stream<SourceFile> parseKotlinFiles(List<Path> kotlinPaths,
                                                ExecutionContext ctx,
                                                RewriteExtension extension,
                                                Path buildDir,
                                                Collection<PathMatcher> exclusions,
                                                Charset javaSourceCharset,
                                                JavaVersion javaVersion,
                                                List<Path> dependencyPaths,
                                                JavaTypeCache javaTypeCache) {
        ParsingExecutionContextView.view(ctx).setCharset(javaSourceCharset);

        return Stream.of((Supplier<KotlinParser>) () -> KotlinParser.builder()
                .classpath(dependencyPaths)
                .styles(styles)
                .typeCache(javaTypeCache)
                .logCompilationWarningsAndErrors(extension.getLogCompilationWarningsAndErrors())
                .build()).map(Supplier::get).flatMap(kp -> kp.parse(kotlinPaths, baseDir, ctx)).map(cu -> {
            if (isExcluded(exclusions, cu.getSourcePath()) || cu.getSourcePath().startsWith(buildDir)) {
                return null;
            }
            return cu;
        }).filter(Objects::nonNull).map(it -> it.withMarkers(it.getMarkers().add(javaVersion)));
    }

    // FIXME: Duplicated from DefaultProjectParser
    private boolean isExcluded(Collection<PathMatcher> exclusions, Path path) {
        for (PathMatcher excluded : exclusions) {
            if (excluded.matches(path)) {
                return true;
            }
        }
        // PathMather will not evaluate the path "build.gradle" to be matched by the pattern "**/build.gradle"
        // This is counter-intuitive for most users and would otherwise require separate exclusions for files at the root and files in subdirectories
        if (!path.isAbsolute() && !path.startsWith(File.separator)) {
            return isExcluded(exclusions, Paths.get("/" + path));
        }
        return false;
    }

    // FIXME: Duplicated from DefaultProjectParser
    private <T extends SourceFile> UnaryOperator<T> addProvenance(Marker sourceSet) {
        return s -> {
            Markers m = s.getMarkers();
            m = m.addIfAbsent(sourceSet);
            return s.withMarkers(m);
        };
    }
}

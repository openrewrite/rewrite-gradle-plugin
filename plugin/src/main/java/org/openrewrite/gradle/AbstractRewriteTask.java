/*
 * Copyright 2020 the original author or authors.
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

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.SourceSet;
import org.openrewrite.gradle.RewriteReflectiveFacade.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public abstract class AbstractRewriteTask extends DefaultTask implements RewriteTask {

    private final Configuration configuration;
    private final SourceSet sourceSet;
    protected final RewriteExtension extension;
    private final RewriteReflectiveFacade rewrite;

    public AbstractRewriteTask(Configuration configuration, SourceSet sourceSet, RewriteExtension extension) {
        this.configuration = configuration;
        this.sourceSet = sourceSet;
        this.extension = extension;
        this.rewrite = new RewriteReflectiveFacade(configuration, extension, this);
    }

    @Internal
    protected abstract Logger getLog();

    /**
     * @return The Java source files that will be subject to rewriting
     */
    @InputFiles
    public FileCollection getJavaSources() {
        return sourceSet.getAllJava();
    }

    @InputFiles
    public FileCollection getResources() {
        return sourceSet.getResources().getSourceDirectories();
    }

    @Input
    public SortedSet<String> getActiveRecipes() {
        return new TreeSet<>(extension.getActiveRecipes());
    }

    @Input
    public SortedSet<String> getActiveStyles() {
        return new TreeSet<>(extension.getActiveStyles());
    }

    @InputFiles
    public FileCollection getDependencies() {
        return sourceSet.getCompileClasspath();
    }

    @Internal
    public SourceSet getSourceSet() {
        return sourceSet;
    }

    /**
     * The prefix used to left-pad log messages, multiplied per "level" of log message.
     */
    private static final String LOG_INDENT_INCREMENT = "    ";

    protected Environment environment() {
        Map<Object, Object> gradleProps = getProject().getProperties().entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue));

        Properties properties = new Properties();
        properties.putAll(gradleProps);

        EnvironmentBuilder env = rewrite.environmentBuilder(properties)
                .scanRuntimeClasspath()
                .scanClasspath(
                        Stream.concat(
                                Stream.concat(
                                        getDependencies().getFiles().stream(),
                                        configuration.getFiles().stream()),
                                getJavaSources().getFiles().stream()
                        )
                                .map(File::toPath)
                                .collect(toList())
                )
                .scanUserHome();

        File rewriteConfig = extension.getConfigFile();
        if (rewriteConfig.exists()) {
            try (FileInputStream is = new FileInputStream(rewriteConfig)) {
                YamlResourceLoader resourceLoader = rewrite.yamlResourceLoader(is, rewriteConfig.toURI(), properties);
                env.load(resourceLoader);
            } catch (IOException e) {
                throw new RuntimeException("Unable to load rewrite configuration", e);
            }
        } else if (extension.getConfigFileSetDeliberately()) {
            getLog().warn("Rewrite configuration file " + rewriteConfig + " does not exist." +
                    "Supplied path: " + rewriteConfig + " configured for project " + getProject().getPath() + " does not exist");
        }

        return env.build();
    }

    protected InMemoryExecutionContext executionContext() {
        return rewrite.inMemoryExecutionContext(t -> getLog().warn(t.getMessage(), t));
    }

    protected ResultsContainer listResults() {
        try {
            Path baseDir = getProject().getRootProject().getRootDir().toPath();

            Environment env = environment();
            Set<String> activeRecipes = getActiveRecipes();
            Set<String> activeStyles = getActiveStyles();
            getLog().quiet(String.format("Using active recipe(s) %s", activeRecipes));
            getLog().quiet(String.format("Using active styles(s) %s", activeStyles));
            if (activeRecipes.isEmpty()) {
                return new ResultsContainer(baseDir, emptyList());
            }
            List<NamedStyles> styles = env.activateStyles(activeStyles);
            Recipe recipe = env.activateRecipes(activeRecipes);

            getLog().quiet("Validating active recipes...");
            Collection<Validated> validated = recipe.validateAll();
            List<Validated.Invalid> failedValidations = validated.stream().map(Validated::failures)
                    .flatMap(Collection::stream).collect(toList());
            if (!failedValidations.isEmpty()) {
                failedValidations.forEach(failedValidation -> getLog().error(
                        "Recipe validation error in " + failedValidation.getProperty() + ": " +
                                failedValidation.getMessage(), failedValidation.getException()));
                if (this.extension.getFailOnInvalidActiveRecipes()) {
                    throw new GradleException("Recipe validation errors detected as part of one or more activeRecipe(s). Please check error logs.");
                } else {
                    getLog().error("Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless.");
                }
            }

            List<SourceFile> sourceFiles = new ArrayList<>();
            List<Path> sourcePaths = getJavaSources().getFiles().stream()
                    .filter(it -> it.isFile() && it.getName().endsWith(".java"))
                    .map(File::toPath)
                    .map(AbstractRewriteTask::toRealPath)
                    .collect(toList());
            List<Path> dependencyPaths = getDependencies().getFiles().stream()
                    .map(File::toPath)
                    .map(AbstractRewriteTask::toRealPath)
                    .collect(toList());
            InMemoryExecutionContext ctx = executionContext();

            getLog().quiet("Parsing Java files...");
            sourceFiles.addAll(
                    rewrite.javaParserFromJavaVersion()
                            .styles(styles)
                            .classpath(dependencyPaths)
                            .logCompilationWarningsAndErrors(false)
                            .build()
                            .parse(sourcePaths, baseDir, ctx)
            );

            getLog().quiet("Parsing YAML files...");
            sourceFiles.addAll(
                    rewrite.yamlParser()
                            .parse(getResources().getFiles().stream()
                                            .filter(it -> it.isFile() && it.getName().endsWith(".yml") || it.getName().endsWith(".yaml"))
                                            .map(File::toPath)
                                            .collect(toList()),
                                    baseDir,
                                    ctx
                            ));

            getLog().quiet("Parsing properties files...");
            sourceFiles.addAll(
                    rewrite.propertiesParser()
                            .parse(
                                    getResources().getFiles().stream()
                                            .filter(it -> it.isFile() && it.getName().endsWith(".properties"))
                                            .map(File::toPath)
                                            .collect(toList()),
                                    baseDir,
                                    ctx
                            ));

            getLog().quiet("Parsing XML files...");
            sourceFiles.addAll(
                    rewrite.xmlParser().parse(
                            getResources().getFiles().stream()
                                    .filter(it -> it.isFile() && it.getName().endsWith(".xml"))
                                    .map(File::toPath)
                                    .collect(toList()),
                            baseDir,
                            ctx
                    ));

            getLog().quiet("Running recipe(s)...");
            List<Result> results = recipe.run(sourceFiles);

            return new ResultsContainer(baseDir, results);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class ResultsContainer {
        final Path projectRoot;
        final List<Result> generated = new ArrayList<>();
        final List<Result> deleted = new ArrayList<>();
        final List<Result> moved = new ArrayList<>();
        final List<Result> refactoredInPlace = new ArrayList<>();

        public ResultsContainer(Path projectRoot, Collection<Result> results) {
            this.projectRoot = projectRoot;
            for (Result result : results) {
                if (result.getBefore() == null && result.getAfter() == null) {
                    // This situation shouldn't happen / makes no sense, log and skip
                    continue;
                }
                if (result.getBefore() == null && result.getAfter() != null) {
                    generated.add(result);
                } else if (result.getBefore() != null && result.getAfter() == null) {
                    deleted.add(result);
                } else if (result.getBefore() != null && !result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())) {
                    moved.add(result);
                } else {
                    refactoredInPlace.add(result);
                }
            }
        }

        public Path getProjectRoot() {
            return projectRoot;
        }

        public boolean isNotEmpty() {
            return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
        }
    }

    protected void logRecipesThatMadeChanges(Result result) {
        for (Recipe recipe : result.getRecipesThatMadeChanges()) {
            getLog().warn(indent(1, recipe.getName()));
        }
    }

    private static Path toRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    protected static String indent(int indent, CharSequence content) {
        StringBuilder prefix = repeat(indent, LOG_INDENT_INCREMENT);
        return prefix.append(content).toString();
    }

    private static StringBuilder repeat(int repeat, String str) {
        StringBuilder buffer = new StringBuilder(repeat * str.length());
        for (int i = 0; i < repeat; i++) {
            buffer.append(str);
        }
        return buffer;
    }
}

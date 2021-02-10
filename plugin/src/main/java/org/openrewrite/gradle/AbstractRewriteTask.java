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

import io.micrometer.core.instrument.MeterRegistry;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.SourceSet;
import org.openrewrite.Recipe;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.yaml.YamlParser;
import org.openrewrite.Result;
import org.openrewrite.config.Environment;
import org.openrewrite.SourceFile;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.java.JavaParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

public abstract class AbstractRewriteTask extends DefaultTask implements RewriteTask {

    private String metricsUri;
    private String metricsUsername;
    private String metricsPassword;
    private MeterRegistry registry;

    private final SourceSet sourceSet;
    private final RewriteExtension extension;

    public AbstractRewriteTask(SourceSet sourceSet, RewriteExtension extension) {
        this.sourceSet = sourceSet;
        this.extension = extension;
    }

    @Internal
    protected abstract Logger getLog();

    /**
     * The Java source files that will be subject to rewriting
     */
    @InputFiles
    public FileCollection getJavaSources() {
        return sourceSet.getAllJava();
    }

    @Override
    public void setMeterRegistry(MeterRegistry registry) {
        this.registry = registry;
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

    protected Environment environment() {
        Environment.Builder env = Environment.builder()
                .scanClasspath(
                        Stream.concat(
                            getDependencies().getFiles().stream(),
                            getJavaSources().getFiles().stream()
                        )
                                .map(File::toPath)
                                .collect(Collectors.toList())
                )
                .scanUserHome();

        File rewriteConfig = extension.getConfigFile();
        if (rewriteConfig != null && rewriteConfig.exists()) {
            try (FileInputStream is = new FileInputStream(rewriteConfig)) {
                Map<Object, Object> gradleProps = getProject().getProperties().entrySet().stream()
                        .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue));
                Properties props = new Properties();
                props.putAll(gradleProps);

                YamlResourceLoader resourceLoader = new YamlResourceLoader(is, rewriteConfig.toURI(), props);
                env.load(resourceLoader);
            } catch (IOException e) {
                throw new RuntimeException("Unable to load rewrite configuration", e);
            }
        } else if(extension.getConfigFileSetDeliberately()) {
            getLog().warn("Rewrite configuration file " + rewriteConfig + " does not exist." +
                    "Supplied path: " + rewriteConfig + " configured for project " + getProject().getPath() + " does not exist");
        }

        return env.build();
    }

    protected ChangesContainer listChanges() {
        try (MeterRegistryProvider meterRegistryProvider = new MeterRegistryProvider(getLog(), metricsUri, metricsUsername, metricsPassword)) {
            MeterRegistry meterRegistry = meterRegistryProvider.registry();

            Path baseDir = getProject().getRootProject().getProjectDir().toPath();

            Environment env = environment();
            Set<String> recipes = getActiveRecipes();
            if (recipes == null || recipes.isEmpty()) {
                return new ChangesContainer(baseDir, emptyList());
            }
            List<NamedStyles> styles = env.activateStyles(getActiveStyles());
            Recipe recipe = env.activateRecipes(recipes);


            List<SourceFile> sourceFiles = new ArrayList<>();
            List<Path> sourcePaths = getJavaSources().getFiles().stream()
                    .filter(it -> it.isFile() && it.getName().endsWith(".java"))
                    .map(File::toPath)
                    .collect(toList());
            List<Path> dependencyPaths = getDependencies().getFiles().stream()
                    .map(File::toPath)
                    .collect(toList());

            sourceFiles.addAll(JavaParser.fromJavaVersion()
                    .styles(styles)
                    .classpath(dependencyPaths)
                    .logCompilationWarningsAndErrors(false)
                    .build()
                    .parse(sourcePaths, baseDir)
            );

            sourceFiles.addAll(new YamlParser().parse(
                    getResources().getFiles().stream()
                            .filter(it -> it.isFile() && it.getName().endsWith(".yml") || it.getName().endsWith(".yaml"))
                            .map(File::toPath)
                            .collect(toList()),
                    baseDir
            ));

            sourceFiles.addAll(new PropertiesParser().parse(
                    getResources().getFiles().stream()
                            .filter(it -> it.isFile() && it.getName().endsWith(".properties"))
                            .map(File::toPath)
                            .collect(toList()),
                    baseDir
            ));

            sourceFiles.addAll(new XmlParser().parse(
                    getResources().getFiles().stream()
                            .filter(it -> it.isFile() && it.getName().endsWith(".xml"))
                            .map(File::toPath)
                            .collect(toList()),
                    baseDir
            ));

            List<Result> results = recipe.run(sourceFiles);

            return new ChangesContainer(baseDir, results);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static class ChangesContainer {
        final Path projectRoot;
        final List<Result> generated = new ArrayList<>();
        final List<Result> deleted = new ArrayList<>();
        final List<Result> moved = new ArrayList<>();
        final List<Result> refactoredInPlace = new ArrayList<>();

        public ChangesContainer(Path projectRoot, Collection<Result> changes) {
            this.projectRoot = projectRoot;
            for (Result change : changes) {
                if (change.getBefore() == null && change.getAfter() == null) {
                    // This situation shouldn't happen / makes no sense, log and skip
                    continue;
                }
                if (change.getBefore() == null && change.getAfter() != null) {
                    generated.add(change);
                } else if (change.getBefore() != null && change.getAfter() == null) {
                    deleted.add(change);
                } else if (change.getBefore() != null && !change.getBefore().getSourcePath().equals(change.getAfter().getSourcePath())) {
                    moved.add(change);
                } else {
                    refactoredInPlace.add(change);
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

    protected void logVisitorsThatMadeChanges(Result change) {
        for (String visitor : change.getRecipesThatMadeChanges()) {
            getLog().warn("  " + visitor);
        }
    }
}

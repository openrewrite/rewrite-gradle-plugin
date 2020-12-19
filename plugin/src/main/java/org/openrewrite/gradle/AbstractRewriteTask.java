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
import org.openrewrite.Refactor;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.yaml.YamlParser;
import org.openrewrite.Change;
import org.openrewrite.Environment;
import org.openrewrite.RefactorVisitor;
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

    @Internal
    protected abstract Logger getLog();

    private String metricsUri;
    private String metricsUsername;
    private String metricsPassword;
    private final List<GradleRecipeConfiguration> recipes = new ArrayList<>();
    private final SortedSet<String> activeRecipes = new TreeSet<>();
    private final SortedSet<String> activeStyles = new TreeSet<>();
    private final SortedSet<String> includes = new TreeSet<>();
    private final SortedSet<String> excludes = new TreeSet<>();
    private FileCollection sources = null;
    private FileCollection dependencies = null;
    private FileCollection resources = null;

    /**
     * The Java source files that will be subject to rewriting
     */
    @InputFiles
    public FileCollection getJavaSources() {
        return sources;
    }

    public void setJavaSources(FileCollection sources) {
        this.sources = sources;
    }


    @InputFiles
    public FileCollection getResources() {
        return resources;
    }

    public void setResources(FileCollection resources) {
        this.resources = resources;
    }

    /**
     * The dependencies required to compile the java source files in #sources
     */
    @Input
    public List<GradleRecipeConfiguration> getRecipes() {
        return recipes;
    }

    @Input
    public SortedSet<String> getActiveRecipes() {
        return activeRecipes;
    }

    @Input
    public SortedSet<String> getActiveStyles() {
        return activeStyles;
    }

    @Input
    public SortedSet<String> getIncludes() {
        return includes;
    }

    @Input
    public SortedSet<String> getExcludes() {
        return excludes;
    }

    @InputFiles
    public FileCollection getDependencies() {
        return dependencies;
    }

    public void setDependencies(FileCollection dependencies) {
        this.dependencies = dependencies;
    }


    private RewriteExtension getExtension() {
        return getProject().getExtensions().findByType(RewriteExtension.class);
    }

    protected Environment environment() {
        Environment.Builder env = Environment.builder()
                .scanClasspath(
                        getJavaSources().getFiles().stream().map(File::toPath).collect(Collectors.toList())
                )
                .scanUserHome();

        File rewriteConfig = getExtension().getConfigFile();
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
        }

        return env.build();
    }

    protected ChangesContainer listChanges() {
        try (MeterRegistryProvider meterRegistryProvider = new MeterRegistryProvider(getLog(), metricsUri, metricsUsername, metricsPassword)) {
            MeterRegistry meterRegistry = meterRegistryProvider.registry();

            Path baseDir = getProject().getRootProject().getProjectDir().toPath();

            Environment env = environment();
            Set<String> recipes = getActiveRecipes();
            if (activeRecipes == null || activeRecipes.isEmpty()) {
                return new ChangesContainer(baseDir, emptyList());
            }
            Collection<RefactorVisitor<?>> visitors = env.visitors(recipes);
            if(visitors.size() == 0) {
                getLog().warn("Could not find any Rewrite visitors matching active recipe(s): " + String.join(", ", activeRecipes) + ". " +
                        "Double check that you have taken a dependency on the jar containing these recipes.");
                return new ChangesContainer(baseDir, emptyList());
            }

            List<SourceFile> sourceFiles = new ArrayList<>();
            List<Path> sourcePaths = getJavaSources().getFiles().stream()
                    .filter(it -> it.isFile() && it.getName().endsWith(".java"))
                    .map(File::toPath)
                    .collect(toList());
            List<Path> dependencyPaths = getDependencies().getFiles().stream()
                    .map(File::toPath)
                    .collect(toList());

            sourceFiles.addAll(JavaParser.fromJavaVersion()
                    .styles(env.styles(activeStyles))
                    .classpath(dependencyPaths)
                    .logCompilationWarningsAndErrors(false)
                    .meterRegistry(meterRegistry)
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

            Collection<Change> changes = new Refactor()
                    .visit(visitors)
                    .setMeterRegistry(meterRegistry)
                    .fix(sourceFiles);

            return new ChangesContainer(baseDir, changes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private MeterRegistry registry;

    @Override
    public void setMeterRegistry(MeterRegistry registry) {
        this.registry = registry;
    }

    public static class ChangesContainer {
        final Path projectRoot;
        final List<Change> generated = new ArrayList<>();
        final List<Change> deleted = new ArrayList<>();
        final List<Change> moved = new ArrayList<>();
        final List<Change> refactoredInPlace = new ArrayList<>();

        public ChangesContainer(Path projectRoot, Collection<Change> changes) {
            this.projectRoot = projectRoot;
            for (Change change : changes) {
                if (change.getOriginal() == null && change.getFixed() == null) {
                    // This situation shouldn't happen / makes no sense, log and skip
                    continue;
                }
                if (change.getOriginal() == null && change.getFixed() != null) {
                    generated.add(change);
                } else if (change.getOriginal() != null && change.getFixed() == null) {
                    deleted.add(change);
                } else if (change.getOriginal() != null && !change.getOriginal().getSourcePath().equals(change.getFixed().getSourcePath())) {
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

        public Stream<Change> stream() {
            return Stream.concat(
                    Stream.concat(generated.stream(), deleted.stream()),
                    Stream.concat(moved.stream(), refactoredInPlace.stream())
            );
        }

    }

    protected void logVisitorsThatMadeChanges(Change change) {
        for (String visitor : change.getVisitorsThatMadeChanges()) {
            getLog().warn("  " + visitor);
        }
    }
}

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
import org.openrewrite.yaml.YamlParser;
import org.openrewrite.Change;
import org.openrewrite.RefactorPlan;
import org.openrewrite.RefactorVisitor;
import org.openrewrite.SourceFile;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.style.ImportLayoutStyle;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

public abstract class AbstractRewriteTask extends DefaultTask implements RewriteTask {

    @Internal
    protected abstract Logger getLog();

    private String metricsUri;
    private String metricsUsername;
    private String metricsPassword;
    private final List<GradleRecipeConfiguration> recipes = new ArrayList<>();
    private final SortedSet<String> activeRecipes = new TreeSet<>();
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

    protected RefactorPlan plan() {
        RefactorPlan.Builder plan = RefactorPlan.builder()
                .compileClasspath(
                       getJavaSources().getFiles().stream().map(File::toPath).collect(Collectors.toList())
                ).scanResources()
                .scanUserHome();

        getRecipes().forEach(recipe -> plan.loadRecipe(recipe.toRecipeConfiguration()));

        File rewriteConfig = getExtension().getConfigFile();
        if(rewriteConfig != null && rewriteConfig.exists()) {
            try (FileInputStream is = new FileInputStream(rewriteConfig)) {
                YamlResourceLoader resourceLoader = new YamlResourceLoader(is);
                plan.loadRecipes(resourceLoader);
                plan.loadVisitors(resourceLoader);
            } catch (IOException e) {
                throw new RuntimeException("Unable to load rewrite configuration", e);
            }
        }

        return plan.build();
    }

    protected Collection<Change> listChanges() {
        try(MeterRegistryProvider meterRegistryProvider = new MeterRegistryProvider(getLog(), metricsUri, metricsUsername, metricsPassword)) {
            MeterRegistry meterRegistry = meterRegistryProvider.registry();

            RefactorPlan plan = plan();
            Set<String> recipes = getActiveRecipes();
            Collection<RefactorVisitor<?>> visitors = plan.visitors(recipes);

            List<SourceFile> sourceFiles = new ArrayList<>();
            List<Path> sourcePaths = getJavaSources().getFiles().stream()
                    .filter(it -> it.isFile() && it.getName().endsWith(".java"))
                    .map(File::toPath)
                    .collect(toList());
            List<Path> dependencyPaths = getDependencies().getFiles().stream()
                    .map(File::toPath)
                    .collect(toList());

            Path projectDir = getProject().getProjectDir().toPath();

            ImportLayoutStyle importLayoutStyle = plan.style(ImportLayoutStyle.class, recipes);
            sourceFiles.addAll(JavaParser.fromJavaVersion()
                    .importStyle(importLayoutStyle)
                    .classpath(dependencyPaths)
                    .logCompilationWarningsAndErrors(false)
                    .meterRegistry(meterRegistry)
                    .build()
                    .parse(sourcePaths, projectDir)
            );

            sourceFiles.addAll(new YamlParser().parse(
                    getResources().getFiles().stream()
                            .filter(it -> it.isFile() && it.getName().endsWith(".yml") || it.getName().endsWith(".yaml"))
                            .map(File::getAbsolutePath)
                            .collect(toList())
            ));

            sourceFiles.addAll(
                    new PropertiesParser().parse(
                        getResources().getFiles().stream()
                        .filter(it -> it.isFile() && it.getName().endsWith(".properties"))
                        .map(File::getAbsolutePath)
                        .collect(toList())
                    )
            );

            return new Refactor().visit(visitors).setMeterRegistry(meterRegistry).fix(sourceFiles);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private MeterRegistry registry;

    @Override
    public void setMeterRegistry(MeterRegistry registry) {
        this.registry = registry;
    }

}

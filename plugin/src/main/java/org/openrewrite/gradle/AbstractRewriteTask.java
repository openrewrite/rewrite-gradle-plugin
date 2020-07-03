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
import org.openrewrite.Change;
import org.openrewrite.RefactorPlan;
import org.openrewrite.SourceVisitor;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

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
    private final List<GradleProfileConfiguration> profiles = new ArrayList<>();
    private final SortedSet<String> activeProfiles = new TreeSet<>(Collections.singletonList("default"));
    private final SortedSet<String> includes = new TreeSet<>();
    private final SortedSet<String> excludes = new TreeSet<>();
    private FileCollection sources = null;
    private FileCollection dependencies = null;

    /**
     * The Java source files that will be subject to rewriting
     */
    @InputFiles
    public FileCollection getSources() {
        return sources;
    }

    /**
     * The dependencies required to compile the java source files in #sources
     */
    @Input
    public List<GradleProfileConfiguration> getProfiles() {
        return profiles;
    }

    @Input
    public SortedSet<String> getActiveProfiles() {
        return activeProfiles;
    }

    @Input
    public SortedSet<String> getIncludes() {
        return includes;
    }

    @Input
    public SortedSet<String> getExcludes() {
        return excludes;
    }

    public void setSources(FileCollection sources) {
        this.sources = sources;
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
                       getSources().getFiles().stream().map(File::toPath).collect(Collectors.toList())
                ).scanResources()
                .scanUserHome();

        getProfiles().forEach(profile -> plan.loadProfile(profile.toProfileConfiguration()));

        File rewriteConfig = getExtension().getConfigFile();
        if(rewriteConfig != null && rewriteConfig.exists()) {
            try (FileInputStream is = new FileInputStream(rewriteConfig)) {
                YamlResourceLoader resourceLoader = new YamlResourceLoader(is);
                plan.loadProfiles(resourceLoader);
                plan.loadVisitors(resourceLoader);
            } catch (IOException e) {
                throw new RuntimeException("Unable to load rewrite configuration", e);
            }
        }

        return plan.build();
    }

    protected List<Change<J.CompilationUnit>> listChanges() {
        try(MeterRegistryProvider meterRegistryProvider = new MeterRegistryProvider(getLog(), metricsUri, metricsUsername, metricsPassword)) {
            MeterRegistry meterRegistry = meterRegistryProvider.registry();

            RefactorPlan plan = plan();
            Collection<SourceVisitor<J>> javaVisitors = plan.visitors(J.class, getActiveProfiles());

            JavaParser.Builder<? extends JavaParser, ?> javaParser;
            try {
                if (System.getProperty("java.version").startsWith("1.8")) {
                    javaParser = (JavaParser.Builder<? extends JavaParser, ?>) Class
                            .forName("org.openrewrite.java.Java8Parser")
                            .getDeclaredMethod("builder")
                            .invoke(null);
                } else {
                    javaParser = (JavaParser.Builder<? extends JavaParser, ?>) Class
                            .forName("org.openrewrite.java.Java11Parser")
                            .getDeclaredMethod("builder")
                            .invoke(null);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to create a Java parser instance. " +
                        "`org.openrewrite:rewrite-java-8` or `org.openrewrite:rewrite-java-11` must be on the plugin classpath.");
            }

            List<Path> sourcePaths = getSources().getFiles().stream().map(File::toPath).collect(toList());
            List<Path> dependencyPaths = getDependencies().getFiles().stream().map(File::toPath).collect(toList());

            List<J.CompilationUnit> cus = javaParser
                    .classpath(dependencyPaths)
                    .logCompilationWarningsAndErrors(false)
                    .meterRegistry(meterRegistry)
                    .build()
                    .parse(sourcePaths, getProject().getProjectDir().toPath());

            return cus.stream()
                    .map(cu -> cu.refactor()
                            .visit(javaVisitors)
                            .setMeterRegistry(meterRegistry)
                            .fix())
                    .filter(change -> !change.getRulesThatMadeChanges().isEmpty())
                    .collect(toList());
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

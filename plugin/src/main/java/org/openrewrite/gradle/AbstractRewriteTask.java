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
package org.openrewrite.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.openrewrite.Result;
import org.openrewrite.config.Environment;
import org.openrewrite.java.tree.J;
import org.openrewrite.scheduling.ForkJoinScheduler;
import org.openrewrite.shaded.jgit.api.Git;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public abstract class AbstractRewriteTask extends DefaultTask {
    private ResolveRewriteDependenciesTask resolveDependenciesTask;
    protected boolean useAstCache;
    private DelegatingProjectParser gpp;

    private DelegatingProjectParser getProjectParser() {
        if(gpp == null) {
            Set<Path> classpath = resolveDependenciesTask.getResolvedDependencies().stream()
                    .map(File::toPath)
                    .collect(Collectors.toSet());
            gpp = new DelegatingProjectParser(getProject().getRootProject(), classpath, useAstCache);
        }
        return gpp;
    }

    protected ResultsContainer listResults() {
        return getProjectParser().listResults();
    }

    protected Environment environment() {
        return getProjectParser().environment();
    }

    @TaskAction
    void run() {
        listResults();
    }

    @Input
    public Set<String> getActiveRecipes() {
        return getProjectParser().getActiveRecipes();
    }

    @Input
    public Set<String> getActiveStyles() {
        return getProjectParser().getActiveStyles();
    }

    protected void shutdownRewrite() {
        J.clearCaches();
        Git.shutdown();
        ForkJoinScheduler.shutdown();
    }

    protected void clearAstCache() {
        getProjectParser().clearAstCache();
    }

    public AbstractRewriteTask setResolveDependenciesTask(ResolveRewriteDependenciesTask resolveDependenciesTask) {
        this.resolveDependenciesTask = resolveDependenciesTask;
        this.dependsOn(resolveDependenciesTask);
        return this;
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
                    // This situation shouldn't happen / makes no sense
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
}

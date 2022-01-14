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
import org.gradle.api.tasks.Internal;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public abstract class AbstractRewriteTask extends DefaultTask {
    private ResolveRewriteDependenciesTask resolveDependenciesTask;
    protected boolean useAstCache;
    private DelegatingProjectParser gpp;
    private RewriteExtension extension;

    public AbstractRewriteTask setExtension(RewriteExtension extension) {
        this.extension = extension;
        return this;
    }

    public AbstractRewriteTask setResolveDependenciesTask(ResolveRewriteDependenciesTask resolveDependenciesTask) {
        this.resolveDependenciesTask = resolveDependenciesTask;
        this.dependsOn(resolveDependenciesTask);
        return this;
    }

    @Internal
    protected DelegatingProjectParser getProjectParser() {
        if(gpp == null) {
            if(extension == null) {
                throw new IllegalArgumentException("Must configure extension");
            }
            if (resolveDependenciesTask == null) {
                throw new IllegalArgumentException("Must configure resolveDependenciesTask");
            }
            Set<Path> classpath = resolveDependenciesTask.getResolvedDependencies().stream()
                    .map(File::toPath)
                    .collect(Collectors.toSet());
            gpp = new DelegatingProjectParser(getProject().getRootProject(), extension, classpath, useAstCache);
        }
        return gpp;
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
        getProjectParser().shutdownRewrite();
    }

    protected void clearAstCache() {
        getProjectParser().clearAstCache();
    }

}

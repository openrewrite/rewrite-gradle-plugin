/*
 * Copyright 2025 the original author or authors.
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
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.options.Option;
import org.gradle.util.GradleVersion;

import javax.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toSet;

public abstract class AbstractRewriteTask extends DefaultTask {
    protected Provider<Set<File>> resolvedDependencies;
    protected boolean dumpGcActivity;
    protected GradleProjectParser gpp;
    protected RewriteExtension extension;

    protected AbstractRewriteTask() {
        if (GradleVersion.current().compareTo(GradleVersion.version("7.4")) >= 0) {
            notCompatibleWithConfigurationCache("org.openrewrite.rewrite needs to parse the whole project");
        }
    }

    public <T extends AbstractRewriteTask> T setExtension(RewriteExtension extension) {
        this.extension = extension;
        //noinspection unchecked
        return (T) this;
    }

    public <T extends AbstractRewriteTask> T setResolvedDependencies(Provider<Set<File>> resolvedDependencies) {
        this.resolvedDependencies = resolvedDependencies;
        //noinspection unchecked
        return (T) this;
    }

    @Option(description = "Dump GC activity related to parsing.", option = "dumpGcActivity")
    public void setDumpGcActivity(boolean dumpGcActivity) {
        this.dumpGcActivity = dumpGcActivity;
    }

    @Input
    public boolean isDumpGcActivity() {
        return dumpGcActivity;
    }

    @Inject
    public ProjectLayout getProjectLayout() {
        throw new AssertionError("unexpected; getProjectLayout() should be overridden by Gradle");
    }

    @Internal
    protected <T extends GradleProjectParser> T getProjectParser() {
        if (gpp == null) {
            if (extension == null) {
                throw new IllegalArgumentException("Must configure extension");
            }
            if (resolvedDependencies == null) {
                throw new IllegalArgumentException("Must configure resolvedDependencies");
            }
            Set<File> deps = resolvedDependencies.getOrNull();
            if (deps == null) {
                deps = emptySet();
            }
            Set<Path> classpath = deps.stream()
                    .map(File::toPath)
                    .collect(toSet());
            gpp = new DelegatingProjectParser(getProject(), extension, classpath);
        }
        //noinspection unchecked
        return (T) gpp;
    }

    @Input
    public List<String> getActiveRecipes() {
        return getProjectParser().getActiveRecipes();
    }

    @Input
    public List<String> getActiveStyles() {
        return getProjectParser().getActiveStyles();
    }

    protected void shutdownRewrite() {
        getProjectParser().shutdownRewrite();
    }

}

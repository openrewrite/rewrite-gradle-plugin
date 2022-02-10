/*
 * Copyright ${year} the original author or authors.
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
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class ResolveRewriteDependenciesTask extends DefaultTask {
    private Set<File> resolvedDependencies;
    private Configuration configuration;
    protected RewriteExtension extension;

    public ResolveRewriteDependenciesTask setConfiguration(Configuration configuration) {
        this.configuration = configuration;
        return this;
    }

    public ResolveRewriteDependenciesTask setExtension(RewriteExtension extension) {
        this.extension = extension;
        return this;
    }

    @Internal
    public Set<File> getResolvedDependencies() {
        if (resolvedDependencies == null) {
            String rewriteVersion = extension.getRewriteVersion();
            Project project = getProject();
            DependencyHandler deps = project.getDependencies();
            Dependency[] dependencies = new Dependency[]{
                    deps.create("org.openrewrite:rewrite-core:" + rewriteVersion),
                    deps.create("org.openrewrite:rewrite-groovy:" + rewriteVersion),
                    deps.create("org.openrewrite:rewrite-gradle:" + rewriteVersion),
                    deps.create("org.openrewrite:rewrite-hcl:" + rewriteVersion),
                    deps.create("org.openrewrite:rewrite-json:" + rewriteVersion),
                    deps.create("org.openrewrite:rewrite-java:" + rewriteVersion),
                    deps.create("org.openrewrite:rewrite-java-11:" + rewriteVersion),
                    deps.create("org.openrewrite:rewrite-java-8:" + rewriteVersion),
                    deps.create("org.openrewrite:rewrite-properties:" + rewriteVersion),
                    deps.create("org.openrewrite:rewrite-xml:" + rewriteVersion),
                    deps.create("org.openrewrite:rewrite-yaml:" + rewriteVersion),

                    // This is an optional dependency of rewrite-java needed when projects also apply the checkstyle plugin
                    deps.create("com.puppycrawl.tools:checkstyle:" + extension.getCheckstyleToolsVersion())
            };
            if (configuration != null) {
                dependencies = Stream.concat(
                        Arrays.stream(dependencies),
                        configuration.getDependencies().stream()
                ).toArray(Dependency[]::new);
            }

            Configuration detachedConf = project.getConfigurations().detachedConfiguration(dependencies);
            // Recent versions of Gradle throw up variant selection errors relating to the Caffeine library
            // Caffeine can be brought in by rewrite-spring's dependency on rewrite-maven
            // But this is a Gradle plugin, so we won't be parsing or refactoring Maven poms in this context
            // Temporarily work around this issue by excluding caffeine
            Map<String, String> exclusion = new HashMap<>();
            exclusion.put("group", "com.github.ben-manes.caffeine");
            exclusion.put("module", "caffeine");
            detachedConf.exclude(exclusion);
            resolvedDependencies = detachedConf.resolve();
        }
        return resolvedDependencies;
    }

    @TaskAction
    void run() {
        getResolvedDependencies();
    }
}

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
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmEnvironment;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE;
import static org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE;

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
            String classpathProp = System.getProperty("moderne.gradle.classpath");
            if (classpathProp != null) {
                try (Stream<Path> paths = Files.walk(Paths.get(classpathProp))) {
                    resolvedDependencies = paths
                            .map(Path::toFile)
                            .collect(Collectors.toSet());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                Dependency[] dependencies = new Dependency[]{
                        deps.create("org.openrewrite:rewrite-core:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-groovy:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-gradle:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-hcl:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-json:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-kotlin:" + extension.getRewriteKotlinVersion()),
                        deps.create("org.openrewrite:rewrite-java:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-java-21:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-java-17:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-java-11:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-java-8:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-maven:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-properties:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-protobuf:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-xml:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-yaml:" + rewriteVersion),
                        deps.create("org.openrewrite:rewrite-polyglot:" + extension.getRewritePolyglotVersion()),
                        deps.create("org.openrewrite.gradle.tooling:model:" + extension.getRewriteGradleModelVersion()),

                        // This is an optional dependency of rewrite-java needed when projects also apply the checkstyle plugin
                        deps.create("com.puppycrawl.tools:checkstyle:" + extension.getCheckstyleToolsVersion()),
                        deps.create("com.fasterxml.jackson.module:jackson-module-kotlin:" + extension.getJacksonModuleKotlinVersion()),
                        deps.create("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:" + extension.getJacksonModuleKotlinVersion())
                };
                if (configuration != null) {
                    dependencies = Stream.concat(
                            Arrays.stream(dependencies),
                            configuration.getDependencies().stream()
                    ).toArray(Dependency[]::new);
                }
                // By using a detached configuration, we separate this dependency resolution from the rest of the project's
                // configuration. This also means that Gradle has no criteria with which to select between variants of
                // dependencies which expose differing capabilities. So those must be manually configured
                Configuration detachedConf = project.getConfigurations().detachedConfiguration(dependencies);

                try {
                    ObjectFactory objectFactory = project.getObjects();
                    detachedConf.attributes(attributes -> {
                        // Taken from org.gradle.api.plugins.jvm.internal.DefaultJvmEcosystemAttributesDetails
                        attributes.attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(Category.class, Category.LIBRARY));
                        attributes.attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.JAVA_RUNTIME));
                        attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objectFactory.named(LibraryElements.class, LibraryElements.JAR));
                        attributes.attribute(BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.class, Bundling.EXTERNAL));
                        attributes.attribute(TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objectFactory.named(TargetJvmEnvironment.class, TargetJvmEnvironment.STANDARD_JVM));
                    });
                } catch (NoClassDefFoundError e) {
                    // Old versions of gradle don't have all of these attributes and that's OK
                }
                resolvedDependencies = detachedConf.resolve();
            }
        }
        return resolvedDependencies;
    }

    @TaskAction
    void run() {
        getResolvedDependencies();
    }
}

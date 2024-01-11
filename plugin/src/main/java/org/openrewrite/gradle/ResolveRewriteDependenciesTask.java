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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;
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
            resolvedDependencies = Optional.of("moderne.gradle.classpath")
                    .map(System::getProperty)
                    .map(this::resolveFromClasspathProperty)
                    .filter(cp -> !cp.isEmpty())
                    .orElseGet(this::resolveFromDetachedConfiguration);
        }
        return resolvedDependencies;
    }

    private Set<File> resolveFromClasspathProperty(String classpathProp) {
        try (Stream<Path> paths = Files.walk(Paths.get(classpathProp))) {
            return paths
                    .map(Path::toFile)
                    .collect(toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Internal
    public Stream<String> getDependenciesToResolve() {
        String rewriteVersion = extension.getRewriteVersion();
        return Stream.of(
                "org.openrewrite:rewrite-core:" + rewriteVersion,
                "org.openrewrite:rewrite-groovy:" + rewriteVersion,
                "org.openrewrite:rewrite-gradle:" + rewriteVersion,
                "org.openrewrite:rewrite-hcl:" + rewriteVersion,
                "org.openrewrite:rewrite-json:" + rewriteVersion,
                "org.openrewrite:rewrite-kotlin:" + extension.getRewriteKotlinVersion(),
                "org.openrewrite:rewrite-java:" + rewriteVersion,
                "org.openrewrite:rewrite-java-21:" + rewriteVersion,
                "org.openrewrite:rewrite-java-17:" + rewriteVersion,
                "org.openrewrite:rewrite-java-11:" + rewriteVersion,
                "org.openrewrite:rewrite-java-8:" + rewriteVersion,
                "org.openrewrite:rewrite-maven:" + rewriteVersion,
                "org.openrewrite:rewrite-properties:" + rewriteVersion,
                "org.openrewrite:rewrite-protobuf:" + rewriteVersion,
                "org.openrewrite:rewrite-xml:" + rewriteVersion,
                "org.openrewrite:rewrite-yaml:" + rewriteVersion,
                "org.openrewrite:rewrite-polyglot:" + extension.getRewritePolyglotVersion(),
                "org.openrewrite.gradle.tooling:model:" + extension.getRewriteGradleModelVersion(),

                // This is an optional dependency of rewrite-java needed when projects also apply the checkstyle plugin
                "com.puppycrawl.tools:checkstyle:" + extension.getCheckstyleToolsVersion(),
                "com.fasterxml.jackson.module:jackson-module-kotlin:" + extension.getJacksonModuleKotlinVersion(),
                "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:" + extension.getJacksonModuleKotlinVersion());
    }

    private Set<File> resolveFromDetachedConfiguration() {
        Project project = getProject();
        DependencyHandler dependencyHandler = project.getDependencies();
        Stream<Dependency> dependencies = getDependenciesToResolve().map(dependencyHandler::create);
        if (configuration != null) {
            dependencies = Stream.concat(dependencies, configuration.getDependencies().stream());
        }
        // By using a detached configuration, we separate this dependency resolution from the rest of the project's
        // configuration. This also means that Gradle has no criteria with which to select between variants of
        // dependencies which expose differing capabilities. So those must be manually configured
        Configuration detachedConf = project.getConfigurations().detachedConfiguration(dependencies.toArray(Dependency[]::new));

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
        return detachedConf.resolve();
    }

    @TaskAction
    void run() {
        getResolvedDependencies();
    }
}

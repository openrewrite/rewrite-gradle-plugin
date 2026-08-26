/*
 * Copyright 2026 the original author or authors.
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
package org.openrewrite.gradle.dependencies;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.*;
import org.gradle.api.attributes.java.TargetJvmEnvironment;
import org.gradle.api.model.ObjectFactory;
import org.openrewrite.gradle.RewriteExtension;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static java.util.stream.Collectors.toList;
import static org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE;
import static org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE;

public class ResolvedDependenciesProvider {
    public static ResolvedDependencies empty() {
        return ResolvedDependencies.EMPTY;
    }

    public static ResolvedDependencies get(Project project, RewriteExtension extension, Configuration rewriteConf) {
        // Avoid Stream.concat here pending https://github.com/gradle/gradle/issues/33152
        List<Dependency> dependencies = new ArrayList<>();
        dependencies.addAll(knownRewriteDependencies(extension, project.getDependencies()));
        dependencies.addAll(rewriteConf.getDependencies());

        List<ProjectDependency> rewriteDependencies = resolveConfiguration(project, knownRewriteDependencies(extension, project.getDependencies()));
        List<ProjectDependency> recipeDependencies = resolveConfiguration(project, rewriteConf.getDependencies());
        List<ProjectDependency> effectiveDependencies = resolveConfiguration(project, dependencies);

        return new ResolvedDependencies(rewriteDependencies, recipeDependencies, effectiveDependencies);
    }

    private static List<ProjectDependency> resolveConfiguration(Project project, Collection<Dependency> dependencies) {
        // By using a detached configuration, we separate this dependency resolution from the rest of the project's
        // configuration. This also means that Gradle has no criteria with which to select between variants of
        // dependencies which expose differing capabilities. So those must be manually configured
        Configuration detachedConf = project.getConfigurations().detachedConfiguration(dependencies.toArray(new Dependency[0]));
        configureAttributes(project, detachedConf);

        return detachedConf.getIncoming()
                .getArtifacts().getArtifacts()
                .stream()
                .map(artifactResult -> {
                    try {
                        return new ProjectDependency(artifactResult);
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(toList());
    }

    private static void configureAttributes(Project project, Configuration configuration) {
        try {
            ObjectFactory objectFactory = project.getObjects();
            configuration.attributes(attributes -> {
                // Adapted from org.gradle.api.plugins.jvm.internal.DefaultJvmEcosystemAttributesDetails
                attributes.attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(Category.class, Category.LIBRARY));
                attributes.attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.JAVA_RUNTIME));
                attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objectFactory.named(LibraryElements.class, LibraryElements.JAR));
                attributes.attribute(BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.class, Bundling.EXTERNAL));
                try {
                    attributes.attribute(TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objectFactory.named(TargetJvmEnvironment.class, TargetJvmEnvironment.STANDARD_JVM));
                } catch (NoClassDefFoundError e) {
                    // Old versions of Gradle don't have the class TargetJvmEnvironment and that's OK, we can always
                    // try this attribute instead
                    attributes.attribute(Attribute.of("org.gradle.jvm.environment", String.class), "standard-jvm");
                }
            });
        } catch (NoClassDefFoundError e) {
            // Old versions of Gradle don't have all of these attributes and that's OK
        }
    }

    private static Collection<Dependency> knownRewriteDependencies(RewriteExtension extension, DependencyHandler deps) {
        String rewriteVersion = extension.getRewriteVersion();
        return Arrays.asList(
                deps.create("org.openrewrite:rewrite-core:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-docker:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-groovy:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-gradle:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-hcl:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-json:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-kotlin:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-java:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-java-25:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-java-21:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-java-17:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-java-11:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-java-8:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-maven:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-properties:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-protobuf:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-toml:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-xml:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-yaml:" + rewriteVersion),
                deps.create("org.openrewrite:rewrite-polyglot:" + extension.getRewritePolyglotVersion()),
                deps.create("org.openrewrite.gradle.tooling:model:" + extension.getRewriteGradleModelVersion()),
                deps.create("com.fasterxml.jackson.module:jackson-module-kotlin:" + extension.getJacksonModuleKotlinVersion()),
                deps.create("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:" + extension.getJacksonModuleKotlinVersion()),
                deps.create("org.rocksdb:rocksdbjni:" + extension.getRocksdbJniVersion())
        );
    }
}

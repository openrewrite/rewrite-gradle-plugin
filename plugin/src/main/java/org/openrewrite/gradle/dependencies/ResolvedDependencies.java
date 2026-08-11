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

import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.jetbrains.annotations.TestOnly;

import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

public class ResolvedDependencies {
    static final ResolvedDependencies EMPTY = new ResolvedDependencies(emptyList(), emptyList(), emptyList());

    private final List<ProjectDependency> rewriteClasspath;
    private final List<ProjectDependency> recipeClasspath;
    private final List<ProjectDependency> effectiveClasspath;

    ResolvedDependencies(List<ProjectDependency> rewriteClasspath, List<ProjectDependency> recipeClasspath, List<ProjectDependency> effectiveClasspath) {
        this.rewriteClasspath = rewriteClasspath;
        this.recipeClasspath = recipeClasspath;
        this.effectiveClasspath = effectiveClasspath;
    }

    @TestOnly // Used by RewritePluginTest
    @SuppressWarnings("unused")
    public List<ProjectDependency> getEffectiveClasspath() {
        return effectiveClasspath;
    }

    /** Returns dependencies only including OpenRewrite's own required dependencies */
    public List<ProjectDependency> getFromRewriteOnly() {
        // Using the effective dependency versions, take the ones that only appear from the (known) rewrite classpath
        return effectiveClasspath.stream()
                .filter(effectiveDependency -> {
                    // Keep if from known rewrite deps
                    ComponentIdentifier expectedIdentifier = effectiveDependency.getIdentifier();
                    return rewriteClasspath.stream()
                            .anyMatch(rewriteDependency -> hasSameDependencyIdentifier(expectedIdentifier, rewriteDependency.getIdentifier()));
                })
                .collect(toList());
    }

    /** Returns dependencies that are <strong>only</strong> present in the recipe's classpath, i.e., excluding rewrite classpath */
    public List<ProjectDependency> getFromRecipeOnly() {
        // Take recipe classpath, remove rewrite modules
        return recipeClasspath.stream()
                .filter(recipeDependency -> {
                    ComponentIdentifier expectedIdentifier = recipeDependency.getIdentifier();

                    // Keep when identifier (independent of version) can't be found in rewrite classpath
                    return rewriteClasspath.stream().noneMatch(dep -> hasSameDependencyIdentifier(expectedIdentifier, dep.getIdentifier()));
                })
                .collect(toList());
    }

    private static boolean hasSameDependencyIdentifier(ComponentIdentifier expectedIdentifier, ComponentIdentifier depIdentifier) {
        if (!(expectedIdentifier instanceof ModuleComponentIdentifier) && !(expectedIdentifier instanceof ProjectComponentIdentifier)) {
            throw new UnsupportedOperationException("Unsupported component identifier type: " + expectedIdentifier.getClass().getName());
        }

        if (expectedIdentifier instanceof ModuleComponentIdentifier) {
            if (!(depIdentifier instanceof ModuleComponentIdentifier)) {
                return false;
            }

            ModuleIdentifier expectedModuleIdentifier = ((ModuleComponentIdentifier) expectedIdentifier).getModuleIdentifier();
            ModuleIdentifier depModuleIdentifier = ((ModuleComponentIdentifier) depIdentifier).getModuleIdentifier();
            return expectedModuleIdentifier.equals(depModuleIdentifier);
        } else if (expectedIdentifier instanceof ProjectComponentIdentifier) {
            if (!(depIdentifier instanceof ProjectComponentIdentifier)) {
                return false;
            }

            return depIdentifier.equals(expectedIdentifier);
        } else {
            throw new UnsupportedOperationException("Unsupported component identifier type: " + depIdentifier.getClass().getName());
        }
    }
}

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
package org.openrewrite.gradle;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * New OpenRewrite and Moderne recipe releases are published to the Code Genome Project rather than to Maven Central.
 * Releases already on Maven Central remain there, so a build that resolves recipes with a dynamic version against
 * Maven Central keeps succeeding while silently pinning itself to the last release published there.
 * <p>
 * This detects that situation so the plugin can point the user at the Code Genome Project. It is informational only.
 */
final class CodeGenomeProjectWarning {

    static final String CREDENTIALS_DOCS = "https://codegenomeproject.org/token";

    private CodeGenomeProjectWarning() {
    }

    /**
     * @param repositoryUrls      the repositories recipe artifacts resolve against
     * @param requestedRecipeDependencies dependencies of the {@code rewrite} configuration, as {@code group:name:version}
     * @return a warning to log, or {@code null} when recipes cannot be silently stale
     */
    static @Nullable String warningFor(Collection<URI> repositoryUrls, Collection<String> requestedRecipeDependencies) {
        if (!resolvesFromMavenCentralOnly(repositoryUrls)) {
            return null;
        }
        Set<String> stale = new LinkedHashSet<>();
        for (String dependency : requestedRecipeDependencies) {
            String[] coordinates = dependency.split(":", 3);
            if (coordinates.length == 3 && isRecipeArtifact(coordinates[0]) && isDynamicVersion(coordinates[2])) {
                stale.add(dependency);
            }
        }
        if (stale.isEmpty()) {
            return null;
        }

        StringBuilder warning = new StringBuilder("These recipe artifacts resolve from Maven Central, which no longer receives new recipe releases:");
        for (String dependency : stale) {
            warning.append("\n    ").append(dependency);
        }
        return warning
                .append("\nNewer recipe versions are published to the Code Genome Project; configure it in your repositories to stop resolving stale recipes.")
                .append("\nSee ").append(CREDENTIALS_DOCS).append(" for credentials and repository configuration.")
                .toString();
    }

    /**
     * Maven Central being absent means recipes come from somewhere that may well be current, such as the Code Genome
     * Project itself or an internal mirror. An unknown repository set, as when repositories are declared in settings
     * rather than in the project, is treated the same way so that the warning stays quiet unless staleness is likely.
     */
    private static boolean resolvesFromMavenCentralOnly(Collection<URI> repositoryUrls) {
        boolean mavenCentral = false;
        for (URI repositoryUrl : repositoryUrls) {
            String host = repositoryUrl.getHost();
            if (host == null) {
                continue;
            }
            if (host.contains("codegenome")) {
                return false;
            }
            mavenCentral |= "repo.maven.apache.org".equals(host) || "repo1.maven.org".equals(host) || "repo2.maven.org".equals(host);
        }
        return mavenCentral;
    }

    private static boolean isRecipeArtifact(String group) {
        return "org.openrewrite".equals(group) || group.startsWith("org.openrewrite.") ||
               "io.moderne".equals(group) || group.startsWith("io.moderne.");
    }

    /**
     * A version the user deliberately pinned is left alone; only a version that asks for "whatever is newest" can
     * quietly resolve to the final Maven Central release.
     */
    private static boolean isDynamicVersion(String version) {
        return version.startsWith("latest.") || version.endsWith("+") ||
               version.startsWith("[") || version.startsWith("(") || version.startsWith("]");
    }
}

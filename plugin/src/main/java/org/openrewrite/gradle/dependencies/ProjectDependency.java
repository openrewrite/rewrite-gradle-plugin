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

import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;

public class ProjectDependency {
    private final Path path;
    private final URL url;
    private final ComponentIdentifier identifier;

    public ProjectDependency(ResolvedArtifactResult artifactResult) throws MalformedURLException {
        this.path = artifactResult.getFile().toPath();
        this.url = path.toUri().toURL();
        this.identifier = artifactResult.getVariant().getOwner();
    }

    public Path getPath() {
        return path;
    }

    public URL getUrl() {
        return url;
    }

    public ComponentIdentifier getIdentifier() {
        return identifier;
    }
}

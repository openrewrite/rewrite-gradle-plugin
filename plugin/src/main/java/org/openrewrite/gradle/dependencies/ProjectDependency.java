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

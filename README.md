<p align="center">
  <a href="https://docs.openrewrite.org">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-dark.svg">
      <source media="(prefers-color-scheme: light)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg">
      <img alt="OpenRewrite Logo" src="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg" width='600px'>
    </picture>
  </a>
</p>

<div align="center">
  <h1>rewrite-gradle-plugin</h1>
</div>

<div align="center">

<!-- Keep the gap above this line, otherwise they won't render correctly! -->
[![ci](https://github.com/openrewrite/rewrite-gradle-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/openrewrite/rewrite-gradle-plugin/actions/workflows/ci.yml)
[![Gradle Plugin Portal](https://img.shields.io/maven-metadata/v/https/plugins.gradle.org/m2/org.openrewrite/plugin/maven-metadata.xml.svg?label=gradlePluginPortal)](https://plugins.gradle.org/plugin/org.openrewrite.rewrite)
[![Apache 2.0](https://img.shields.io/github/license/openrewrite/rewrite-gradle-plugin.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Revved up by Develocity](https://img.shields.io/badge/Revved%20up%20by-Develocity-06A0CE?logo=Gradle&labelColor=02303A)](https://ge.openrewrite.org/scans)
[![Contributing Guide](https://img.shields.io/badge/Contributing-Guide-informational)](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md)
</div>

## What is this?

This project provides a Gradle plugin that applies [Rewrite](https://github.com/openrewrite/rewrite) checking and fixing tasks as build tasks, one of several possible workflows for propagating change across an organization's source code.

```groovy
plugins {
    id("java")
    id("org.openrewrite.rewrite").version("latest_version_here")
}

rewrite {
    // Reformats Java Code
    activeRecipe("org.openrewrite.java.format.AutoFormat")
}
```

### Consuming latest snapshots from OSSRH

To use the latest `-SNAPSHOT` of the `rewrite-gradle-plugin`, update your project's `settings.gradle.kts`:

```kts
pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == "org.openrewrite") {
                useModule("org.openrewrite:plugin:${requested.version}")
            }
        }
    }

    repositories {
        // ...
        maven {
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
        }
        // ...
        // you'll likely also need this if you don't have a pluginManagement section already:
        gradlePluginPortal()
        // ...
    }
}
```

The plugin can be consumed in your `build.gradle.kts`:

```kts
plugins {
    id("org.openrewrite.rewrite") version "X.Y.Z-SNAPSHOT"
    // or resolved dynamically to absolute latest:
    id("org.openrewrite.rewrite") version "latest.integration"
}
```

## Contributing

We appreciate all types of contributions. See the [contributing guide](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md) for detailed instructions on how to get started.

## Documentation

- [OpenRewrite Quickstart Guide](https://docs.openrewrite.org/running-recipes/getting-started)
- [Gradle Plugin Reference](https://docs.openrewrite.org/reference/gradle-plugin-configuration)

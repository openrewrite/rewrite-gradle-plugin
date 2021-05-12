![Logo](https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss.png)
### Eliminate Tech-Debt. At build time.

[![ci](https://github.com/openrewrite/rewrite-gradle-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/openrewrite/rewrite-gradle-plugin/actions/workflows/ci.yml)
[![Gradle Plugin Portal](https://img.shields.io/maven-metadata/v/https/plugins.gradle.org/m2/org.openrewrite/plugin/maven-metadata.xml.svg?label=gradlePluginPortal)](https://plugins.gradle.org/plugin/org.openrewrite.rewrite)
[![Apache 2.0](https://img.shields.io/github/license/openrewrite/rewrite-gradle-plugin.svg)](https://www.apache.org/licenses/LICENSE-2.0)

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

## Documentation

- [OpenRewrite Quickstart Guide](https://docs.openrewrite.org/getting-started/getting-started)
- [Gradle Plugin Reference](https://docs.openrewrite.org/reference/gradle-plugin-configuration)

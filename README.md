![Logo](https://github.com/openrewrite/rewrite/raw/master/doc/logo-oss.png)
### Eliminate Tech-Debt. At build time.

[![Build Status](https://circleci.com/gh/openrewrite/rewrite-gradle-plugin.svg?style=shield)](https://circleci.com/gh/openrewrite/rewrite-gradle-plugin)
[![Apache 2.0](https://img.shields.io/github/license/openrewrite/rewrite-gradle-plugin.svg)](https://www.apache.org/licenses/LICENSE-2.0)

## What is this?

This project provides a Gradle plugin that applies [Rewrite](https://github.com/openrewrite/rewrite) checking and fixing tasks as build tasks, one of several possible workflows for propagating change across an organization's source code.

```groovy
plugins {
    id("java")
    id("org.openrewrite.rewrite").version("3.0.0-rc.2")
}

rewrite {
    // Reformats Java Code 
    activeRecipe("org.openrewrite.java.format.AutoFormat")
}
```

## Documentation

[Quick Start Guide](https://docs.openrewrite.org/getting-started/getting-started) 
[Gradle Plugin Reference](https://docs.openrewrite.org/reference/gradle-plugin-configuration)

## Future development

See the [Rewrite Roadmap](https://github.com/orgs/openrewrite/projects/2). 

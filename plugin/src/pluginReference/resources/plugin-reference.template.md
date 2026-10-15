# OpenRewrite Gradle Plugin Reference

Automatically eliminate technical debt. Apply OpenRewrite recipes to refactor, migrate, and fix source code across Java, Kotlin, Gradle, XML, YAML, properties, and more.

New to OpenRewrite? Start with the [quickstart guide](https://docs.openrewrite.org/running-recipes/getting-started), or browse the [recipe catalog](https://docs.openrewrite.org/recipes).

## Getting started

The plugin is published to the [Code Genome Project](https://codegenomeproject.org/) rather than the Gradle Plugin Portal, since it resolves `org.openrewrite` artifacts that are only available there. Add the repository to `settings.gradle.kts` or `settings.gradle`:

::: dsl-tabs

```kotlin
pluginManagement {
    repositories {
        maven {
            name = "codegenome"
            url = uri("https://artifacts.codegenomeproject.org/maven")
            credentials(PasswordCredentials::class)
        }
        gradlePluginPortal()
    }
}
```

```groovy
pluginManagement {
    repositories {
        maven {
            name = 'codegenome'
            url = 'https://artifacts.codegenomeproject.org/maven'
            credentials(PasswordCredentials)
        }
        gradlePluginPortal()
    }
}
```

:::

The repository requires authentication. [Sign in to get a download token](https://codegenomeproject.org/token), then add your username and token to `~/.gradle/gradle.properties`:

```properties
codegenomeUsername=you@example.com
codegenomePassword=<token>
```

Then apply the plugin in `build.gradle.kts` or `build.gradle`, add the recipe modules you need to the `rewrite` configuration, and activate a recipe:

::: dsl-tabs

```kotlin
plugins {
    id("org.openrewrite.rewrite") version "X.Y.Z"
}

repositories {
    mavenCentral()
    maven {
        name = "codegenome"
        url = uri("https://artifacts.codegenomeproject.org/maven")
        credentials(PasswordCredentials::class)
    }
}

dependencies {
    rewrite(platform("org.openrewrite.recipe:rewrite-recipe-bom:latest.release"))
    rewrite("org.openrewrite.recipe:rewrite-static-analysis")
}

rewrite {
    activeRecipe("org.openrewrite.staticanalysis.CommonStaticAnalysis")
}
```

```groovy
plugins {
    id 'org.openrewrite.rewrite' version 'X.Y.Z'
}

repositories {
    mavenCentral()
    maven {
        name = 'codegenome'
        url = 'https://artifacts.codegenomeproject.org/maven'
        credentials(PasswordCredentials)
    }
}

dependencies {
    rewrite(platform('org.openrewrite.recipe:rewrite-recipe-bom:latest.release'))
    rewrite('org.openrewrite.recipe:rewrite-static-analysis')
}

rewrite {
    activeRecipe('org.openrewrite.staticanalysis.CommonStaticAnalysis')
}
```

:::

Preview the changes as a patch in `build/reports/rewrite/rewrite.patch`, then apply them:

```bash
./gradlew rewriteDryRun
./gradlew rewriteRun
```

## Tasks

{{tasks}}

## Configuration

The plugin is configured via the `rewrite` DSL block in your `build.gradle.kts` or `build.gradle`:

::: dsl-tabs

```kotlin
rewrite {
    activeRecipe("org.openrewrite.staticanalysis.CommonStaticAnalysis")
    exclusion("src/generated/**")
    failOnDryRunResults = true
}
```

```groovy
rewrite {
    activeRecipe('org.openrewrite.staticanalysis.CommonStaticAnalysis')
    exclusion('src/generated/**')
    failOnDryRunResults = true
}
```

:::

Applied to the root project of a multi-project build, the plugin covers every subproject, and the root project's `rewrite` DSL and `rewrite` dependency configuration apply to the whole build. Applied to a subproject, it covers only that subproject.

### Properties

In the Kotlin DSL, boolean properties with an `is` getter keep that prefix: {{kotlinIsProperties}}.

{{properties}}

<details id="default-plain-text-masks">

<summary>Default plain text masks</summary>

{{plainTextMasks}}

</details>

### Methods

{{methods}}

## Command-line overrides

System properties take precedence over the `rewrite` DSL, so you can run a recipe once without editing the build. The recipe still has to be on the `rewrite` classpath.

```bash
./gradlew rewriteRun -Drewrite.activeRecipe=org.openrewrite.staticanalysis.CommonStaticAnalysis
```

| Property | Effect |
|------------|------------------|
| `-Drewrite.activeRecipe=<names>` | Replaces `activeRecipes` with a comma-separated list of recipe names. `rewrite.activeRecipes` is accepted too. |
| `-Drewrite.activeStyle=<names>` | Replaces `activeStyles` with a comma-separated list of style names. `rewrite.activeStyles` is accepted too. |
| `-Prewrite.throwOnParseFailures` | Enables `throwOnParseFailures`. |

## Javadoc

Full API documentation is available in the [Javadoc](apidocs/index.html).

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

| Task | Description |
| ---- | ------------ |
| `rewriteRun` | Apply the active refactoring recipes. Source files will be modified in place. |
| `rewriteDryRun` | Run the active refactoring recipes, producing a patch file. No source files will be changed. |
| `rewriteDiscover` | Lists all available recipes, their visitors, and active recipes configured in the rewrite DSL or rewrite.yml |

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

In the Kotlin DSL, boolean properties with an `is` getter keep that prefix: `isEnableExperimentalGradleBuildScriptParsing`, `isExportDatatables`.

| Property | Type | Default | Description |
| ---------------- | -------- | -------- | ------------------------ |
| `activeRecipes` | `List<String>` | Empty list | Fully qualified class names of recipes to activate. Recipes will only run when explicitly activated here or in a rewrite.yml file. |
| `activeStyles` | `List<String>` | Empty list | Fully qualified class names of styles to activate. Styles will only be applied when explicitly activated here or in a rewrite.yml file. |
| `configFile` | `File` | `rewrite.yml` in the project directory | Path to the OpenRewrite YAML configuration file. |
| `checkstyleConfigFile` | `File` | `null` | Optional path to a Checkstyle configuration file. When set, OpenRewrite will use it to inform Java code style decisions. If not set explicitly, the plugin will attempt to auto-detect a Checkstyle configuration from the Checkstyle Gradle plugin. |
| `enableExperimentalGradleBuildScriptParsing` | `boolean` | `true` | Whether to parse Gradle build scripts (`build.gradle`) as part of the source set. |
| `exportDatatables` | `boolean` | `false` | Whether to export data tables to `<build directory>/reports/rewrite/datatables/<timestamp>`. |
| `exclusions` | `List<String>` | Empty list | Glob patterns for files to exclude from processing. For example: `"src/generated/**"`. |
| `plainTextMasks` | `List<String>` | [Built-in list](#default-plain-text-masks) | Glob patterns for files that should be parsed as plain text. Exclusions take precedence over plain text masks. |
| `sizeThresholdMb` | `int` | `10` | Maximum file size in megabytes. Source files larger than this threshold are skipped during parsing. |
| `rewriteVersion` | `String` | `null` | Override the version of rewrite core libraries to be used. When `null`, the version bundled with the plugin is used. |
| `logCompilationWarningsAndErrors` | `boolean` | `false` | Whether to log Java compilation warnings and errors encountered during parsing. |
| `failOnInvalidActiveRecipes` | `boolean` | `false` | Whether to throw an exception if an activeRecipe fails configuration validation. This may happen if the activeRecipe is improperly configured, or any downstream recipes are improperly configured. For now, this defaults to `false` to prevent one improperly configured recipe from failing the build. In the future, the default may change to `true` to be more restrictive. |
| `failOnDryRunResults` | `boolean` | `false` | Whether `rewriteDryRun` should fail the build when it detects that changes would be made. Useful in CI to enforce that all recipes have already been applied. |
| `throwOnParseFailures` | `boolean` | `false` | Whether to throw an exception when source file parsing fails. Can also be enabled via the project property `-Prewrite.throwOnParseFailures`. |
| `pomCacheEnabled` | `boolean` | `true` | Whether to cache resolved Maven POMs to a RocksDB-backed on-disk cache to speed up subsequent recipe runs. When enabled, a two-layer cache is used: an in-memory L1 cache backed by a RocksDB L2 cache. When disabled, only an in-memory cache is used. |
| `pomCacheDirectory` | `String` | `~/.rewrite-cache` | Directory in which to store the RocksDB-backed Maven POM cache. |

<details id="default-plain-text-masks">

<summary>Default plain text masks</summary>

`**/*.adoc` `**/*.aj` `**/*.bash` `**/*.bat` `**/CODEOWNERS` `**/*.css` `**/*.config` `**/Dockerfile*` `**/*.env` `**/.gitattributes` `**/.gitignore` `**/*.htm*` `**/gradlew` `**/.java-version` `**/*.jelly` `**/*.jsp` `**/*.ksh` `**/*.lock` `**/lombok.config` `**/[mM]akefile` `**/*.md` `**/*.mf` `**/META-INF/services/**` `**/META-INF/spring/**` `**/META-INF/spring.factories` `**/mvnw` `**/*.qute.java` `**/.sdkmanrc` `**/*.sh` `**/*.sql` `**/*.svg` `**/*.tsx` `**/*.txt`

</details>

### Methods

| Method | Description |
| ---- | ------------ |
| `activeRecipe(String...)` | Adds recipes to `activeRecipes`. |
| `clearActiveRecipes()` | Removes all active recipes, for example ones added by a convention plugin. |
| `activeStyle(String...)` | Adds styles to `activeStyles`. |
| `clearActiveStyles()` | Removes all active styles. |
| `exclusion(String...)` | Adds glob patterns to `exclusions`. Also accepts a `Collection<String>`. |
| `plainTextMask(String...)` | Adds glob patterns to `plainTextMasks`. Specifying any mask replaces the built-in list. Also accepts a `Collection<String>`. |

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

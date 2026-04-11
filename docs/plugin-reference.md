# OpenRewrite Gradle Plugin Reference

Automatically eliminate technical debt. Apply OpenRewrite recipes to refactor, migrate, and fix source code across Java, Kotlin, Gradle, XML, YAML, properties, and more.

## Tasks

### `rewriteRun`

Apply the active refactoring recipes. Source files will be modified in place.

### `rewriteDryRun`

Run the active refactoring recipes, producing a patch file. No source files will be changed.

### `rewriteDiscover`

Lists all available recipes, their visitors, and active recipes configured in the rewrite DSL or rewrite.yml

## Configuration

The plugin is configured via the `rewrite` DSL block in your `build.gradle` or `build.gradle.kts`:

```groovy
rewrite {
    activeRecipe("org.openrewrite.java.format.AutoFormat")
    exclusion("src/generated/**")
}
```

### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `activeRecipes` | `List<String>` | Empty list | Fully qualified class names of recipes to activate. Recipes will only run when explicitly activated here or in a rewrite.yml file. |
| `activeStyles` | `List<String>` | Empty list | Fully qualified class names of styles to activate. Styles will only be applied when explicitly activated here or in a rewrite.yml file. |
| `configFile` | `File` | `rewrite.yml` | Path to the OpenRewrite YAML configuration file. Defaults to `rewrite.yml` in the project directory. |
| `checkstyleConfigFile` | `File` | `null` | Optional path to a Checkstyle configuration file. When set, OpenRewrite will use it to inform Java code style decisions. If not set explicitly, the plugin will attempt to auto-detect a Checkstyle configuration from the Checkstyle Gradle plugin. |
| `enableExperimentalGradleBuildScriptParsing` | `boolean` | `true` | Whether to parse Gradle build scripts (`build.gradle`) as part of the source set. Defaults to `true`. |
| `exportDatatables` | `boolean` | `false` | Whether to export data tables to `<build directory>/reports/rewrite/datatables/<timestamp>`. Defaults to `false`. |
| `exclusions` | `List<String>` | Empty list | Glob patterns for files to exclude from processing. For example: `"src/generated/**"`. |
| `plainTextMasks` | `List<String>` | Empty list | Glob patterns for files that should be parsed as plain text. Defaults to a comprehensive list including `**/*.md`, `**/*.sql`, `**/*.txt`, and others. Exclusions take precedence over plain text masks. |
| `sizeThresholdMb` | `int` | `10` | Maximum file size in megabytes. Source files larger than this threshold are skipped during parsing. Defaults to `10`. |
| `rewriteVersion` | `String` | `null` | Override the version of rewrite core libraries to be used. When `null`, the version bundled with the plugin is used. |
| `logCompilationWarningsAndErrors` | `boolean` | `false` | Whether to log Java compilation warnings and errors encountered during parsing. Defaults to `false`. |
| `failOnInvalidActiveRecipes` | `boolean` | `false` | Whether to throw an exception if an activeRecipe fails configuration validation. This may happen if the activeRecipe is improperly configured, or any downstream recipes are improperly configured. For the time, this default is "false" to prevent one improperly configured recipe from failing the build. In the future, this default may be changed to "true" to be more restrictive. |
| `failOnDryRunResults` | `boolean` | `false` | Whether `rewriteDryRun` should fail the build when it detects that changes would be made. Useful in CI to enforce that all recipes have already been applied. Defaults to `false`. |
| `throwOnParseFailures` | `boolean` | `false` | Whether to throw an exception when source file parsing fails. Can also be enabled via the project property `-Prewrite.throwOnParseFailures`. Defaults to `false`. |

## Javadoc

Full API documentation is available in the [Javadoc](apidocs/index.html).


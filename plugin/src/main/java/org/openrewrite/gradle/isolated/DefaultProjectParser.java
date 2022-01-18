/*
 * Copyright ${year} the original author or authors.
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
package org.openrewrite.gradle.isolated;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.openrewrite.*;
import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.config.YamlResourceLoader;

import org.openrewrite.gradle.DefaultRewriteExtension;
import org.openrewrite.gradle.GradleProjectParser;
import org.openrewrite.gradle.RewriteExtension;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.java.style.CheckstyleConfigLoader;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.BuildTool;
import org.openrewrite.marker.GitProvenance;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;
import org.openrewrite.shaded.jgit.api.Git;
import org.openrewrite.style.NamedStyles;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.internal.ListUtils.map;

@SuppressWarnings("unused")
public class DefaultProjectParser implements GradleProjectParser {
    private final Logger logger = Logging.getLogger(DefaultProjectParser.class);
    private final Path baseDir;
    private final RewriteExtension extension;
    private final Project rootProject;
    private final List<Marker> sharedProvenance;
    private final Map<String, Object> astCache;

    private List<NamedStyles> styles = null;
    private Environment environment = null;

    public DefaultProjectParser(Project rootProject, RewriteExtension extension, Map<String, Object> astCache) {
        this.baseDir = rootProject.getRootDir().toPath();
        this.extension = extension;
        this.rootProject = rootProject;
        this.astCache = astCache;

        sharedProvenance = Stream.of(gitProvenance(baseDir),
                        new BuildTool(randomId(), BuildTool.Type.Gradle, rootProject.getGradle().getGradleVersion()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Nullable
    private GitProvenance gitProvenance(Path baseDir) {
        try {
            return GitProvenance.fromProjectDirectory(baseDir);
        } catch(Exception e) {
            // Logging at a low level as this is unlikely to happen except in non-git projects, where it is expected
            logger.debug("Unable to determine git provenance", e);
        }
        return null;
    }

    public SortedSet<String> getActiveRecipes() {
        String activeRecipeProp = System.getProperty("activeRecipe");
        if(activeRecipeProp == null) {
            return new TreeSet<>(extension.getActiveRecipes());
        } else {
            return new TreeSet<>(Collections.singleton(activeRecipeProp));
        }
    }

    public SortedSet<String> getActiveStyles() {
        return new TreeSet<>(extension.getActiveStyles());
    }

    public SortedSet<String> getAvailableStyles() {
        return environment().listStyles().stream().map(NamedStyles::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    public Collection<RecipeDescriptor> listRecipeDescriptors() {
        return environment().listRecipeDescriptors();
    }

    @Override
    public Collection<Path> listSources(Project project) {
        // Use a sorted collection so that gradle input detection isn't thrown off by ordering
        Set<Path> result = new TreeSet<>();
        ResourceParser rp = new ResourceParser(extension.getExclusions(), extension.getSizeThresholdMb());
        rp.listSources(baseDir, project.getProjectDir().toPath());
        //noinspection deprecation
        JavaPluginConvention javaConvention = project.getConvention().findPlugin(JavaPluginConvention.class);
        if (javaConvention != null) {
            for (SourceSet sourceSet : javaConvention.getSourceSets()) {
                sourceSet.getAllJava().getFiles().stream()
                        .filter(it -> it.isFile() && it.getName().endsWith(".java"))
                        .map(File::toPath)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .forEach(result::add);
            }
        }
        for(Project subproject : project.getSubprojects()) {
            result.addAll(listSources(subproject));
        }
        return result;
    }

    @Override
    public void dryRun(Path reportPath, boolean useAstCache) {
        try {
            ResultsContainer results = listResults(useAstCache);

            if (results.isNotEmpty()) {
                for (Result result : results.generated) {
                    assert result.getAfter() != null;
                    logger.warn("These recipes would generate new file {}:", result.getAfter().getSourcePath());
                    logRecipesThatMadeChanges(result);
                }
                for (Result result : results.deleted) {
                    assert result.getBefore() != null;
                    logger.warn("These recipes would delete file {}:", result.getBefore().getSourcePath());
                    logRecipesThatMadeChanges(result);
                }
                for (Result result : results.moved) {
                    assert result.getBefore() != null;
                    assert result.getAfter() != null;
                    logger.warn("These recipes would move file from {} to {}:", result.getBefore().getSourcePath(), result.getAfter().getSourcePath());
                    logRecipesThatMadeChanges(result);
                }
                for (Result result : results.refactoredInPlace) {
                    assert result.getBefore() != null;
                    logger.warn("These recipes would make results to {}:", result.getBefore().getSourcePath());
                    logRecipesThatMadeChanges(result);
                }

                //noinspection ResultOfMethodCallIgnored
                reportPath.getParent().toFile().mkdirs();
                try (BufferedWriter writer = Files.newBufferedWriter(reportPath)) {
                    Stream.concat(
                                    Stream.concat(results.generated.stream(), results.deleted.stream()),
                                    Stream.concat(results.moved.stream(), results.refactoredInPlace.stream())
                            )
                            .map(Result::diff)
                            .forEach(diff -> {
                                try {
                                    writer.write(diff + "\n");
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                } catch (Exception e) {
                    throw new RuntimeException("Unable to generate rewrite result file.", e);
                }
                logger.warn("Report available:");
                logger.warn("    " + reportPath.normalize());
                logger.warn("Run 'gradle rewriteRun' to apply the recipes.");

                if (rootProject.getExtensions().getByType(DefaultRewriteExtension.class).getFailOnDryRunResults()) {
                    throw new RuntimeException("Applying recipes would make changes. See logs for more details.");
                }
            } else {
                logger.lifecycle("Applying recipes would make no changes. No report generated.");
            }
        } finally {
            shutdownRewrite();
        }
    }

    @Override
    public void run(boolean useAstCache) {
        try {
            ResultsContainer results = listResults(useAstCache);

            if (results.isNotEmpty()) {
                for (Result result : results.generated) {
                    assert result.getAfter() != null;
                    logger.lifecycle("Generated new file " +
                            result.getAfter().getSourcePath() +
                            " by:");
                    logRecipesThatMadeChanges(result);
                }
                for (Result result : results.deleted) {
                    assert result.getBefore() != null;
                    logger.lifecycle("Deleted file " +
                            result.getBefore().getSourcePath() +
                            " by:");
                    logRecipesThatMadeChanges(result);
                }
                for (Result result : results.moved) {
                    assert result.getAfter() != null;
                    assert result.getBefore() != null;
                    logger.lifecycle("File has been moved from " +
                            result.getBefore().getSourcePath() + " to " +
                            result.getAfter().getSourcePath() + " by:");
                    logRecipesThatMadeChanges(result);
                }
                for (Result result : results.refactoredInPlace) {
                    assert result.getBefore() != null;
                    logger.lifecycle("Changes have been made to " +
                            result.getBefore().getSourcePath() +
                            " by:");
                    logRecipesThatMadeChanges(result);
                }

                logger.lifecycle("Please review and commit the results.");

                try {
                    for (Result result : results.generated) {
                        assert result.getAfter() != null;
                        try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                                results.getProjectRoot().resolve(result.getAfter().getSourcePath()))) {
                            sourceFileWriter.write(result.getAfter().printAll());
                        }
                    }
                    for (Result result : results.deleted) {
                        assert result.getBefore() != null;
                        Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath());
                        boolean deleteSucceeded = originalLocation.toFile().delete();
                        if (!deleteSucceeded) {
                            throw new IOException("Unable to delete file " + originalLocation.toAbsolutePath());
                        }
                    }
                    for (Result result : results.moved) {
                        // Should we try to use git to move the file first, and only if that fails fall back to this?
                        assert result.getBefore() != null;
                        Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath());

                        // On Mac this can return "false" even when the file was deleted, so skip the check
                        //noinspection ResultOfMethodCallIgnored
                        originalLocation.toFile().delete();

                        assert result.getAfter() != null;
                        // Ensure directories exist in case something was moved into a hitherto non-existent package
                        Path afterLocation = results.getProjectRoot().resolve(result.getAfter().getSourcePath());
                        File parentDir = afterLocation.toFile().getParentFile();
                        //noinspection ResultOfMethodCallIgnored
                        parentDir.mkdirs();
                        try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(afterLocation)) {
                            sourceFileWriter.write(result.getAfter().printAll());
                        }
                    }
                    for (Result result : results.refactoredInPlace) {
                        assert result.getBefore() != null;
                        try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                                results.getProjectRoot().resolve(result.getBefore().getSourcePath()))) {
                            assert result.getAfter() != null;
                            sourceFileWriter.write(result.getAfter().printAll());
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Unable to rewrite source files", e);
                }
            }
        } finally {
            shutdownRewrite();
        }
    }

    private Environment environment() {
        if(environment == null) {
            Map<Object, Object> gradleProps = rootProject.getProperties().entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                    .collect(toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue));

            Properties properties = new Properties();
            properties.putAll(gradleProps);
            GradlePropertiesHelper.checkAndLogMissingJvmModuleExports((String) gradleProps.getOrDefault("org.gradle.jvmargs", ""));

            Environment.Builder env = Environment.builder();
            env.scanClassLoader(getClass().getClassLoader());

            File rewriteConfig = extension.getConfigFile();
            if (rewriteConfig.exists()) {
                try (FileInputStream is = new FileInputStream(rewriteConfig)) {
                    YamlResourceLoader resourceLoader = new YamlResourceLoader(is, rewriteConfig.toURI(), properties, getClass().getClassLoader());
                    env.load(resourceLoader);
                } catch (IOException e) {
                    throw new RuntimeException("Unable to load rewrite configuration", e);
                }
            } else if (extension.getConfigFileSetDeliberately()) {
                logger.warn("Rewrite configuration file " + rewriteConfig + " does not exist.");
            }

            environment = env.build();
        }
        return environment;
    }

    public List<SourceFile> parse() {
        Environment env = environment();
        ExecutionContext ctx = new InMemoryExecutionContext(t -> logger.warn(t.getMessage(), t));
        List<SourceFile> sourceFiles = new ArrayList<>();
        Set<Path> alreadyParsed = new HashSet<>();
        for(Project subProject : rootProject.getSubprojects()) {
            sourceFiles.addAll(parse(subProject, alreadyParsed, ctx));
        }
        sourceFiles.addAll(parse(rootProject, alreadyParsed, ctx));

        return sourceFiles;
    }

    public List<SourceFile> parse(Project subproject, Set<Path> alreadyParsed, ExecutionContext ctx) {
        try {
            logger.lifecycle("Parsing sources from project {}", subproject.getName());
            List<NamedStyles> styles = getStyles();
            @SuppressWarnings("deprecation")
            JavaPluginConvention javaConvention = subproject.getConvention().findPlugin(JavaPluginConvention.class);
            Set<SourceSet> sourceSets;
            List<Marker> projectProvenance;
            if(javaConvention == null) {
                projectProvenance = sharedProvenance;
                sourceSets = Collections.emptySet();
            } else {
                projectProvenance = new ArrayList<>(sharedProvenance);
                projectProvenance.add(new JavaVersion(randomId(), System.getProperty("java.runtime.version"),
                        System.getProperty("java.vm.vendor"),
                        javaConvention.getSourceCompatibility().toString(),
                        javaConvention.getTargetCompatibility().toString()));
                projectProvenance.add(new JavaProject(randomId(), subproject.getName(),
                        new JavaProject.Publication(subproject.getGroup().toString(),
                                subproject.getName(),
                                subproject.getVersion().toString())));
                sourceSets = javaConvention.getSourceSets();
            }

            ResourceParser rp = new ResourceParser(extension.getExclusions(), extension.getSizeThresholdMb());

            List<SourceFile> sourceFiles = new ArrayList<>();
            if(extension.isEnableExperimentalGradleBuildScriptParsing()) {
                logger.warn("Rewrite of Gradle files is an incubating feature which has been disabled in this release because it needs a bit more time to bake.");
//                File buildScriptFile = subproject.getBuildFile();
//                try {
//                    if (buildScriptFile.toString().toLowerCase().endsWith(".gradle") && buildScriptFile.exists()) {
//                        GradleParser gradleParser = new GradleParser(
//                                GroovyParser.builder()
//                                        .styles(styles)
//                                        .logCompilationWarningsAndErrors(true));
//
//                        sourceFiles.addAll(gradleParser.parse(singleton(buildScriptFile.toPath()), baseDir, ctx));
//                    }
//                } catch (Exception e) {
//                    logger.warn("Problem with parsing gradle script at \"" + buildScriptFile.getAbsolutePath()  + "\" : ", e);
//                }
            }

            for(SourceSet sourceSet : sourceSets) {
                List<Path> javaPaths = sourceSet.getAllJava().getFiles().stream()
                        .filter(it -> it.isFile() && it.getName().endsWith(".java"))
                        .map(File::toPath)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .collect(toList());
                // The compile classpath doesn't include the transitive dependencies of the implementation configuration
                // These aren't needed for compilation, but we want them so recipes have access to comprehensive type information
                // The implementation configuration isn't resolvable, so we need a new configuration that extends from it
                Configuration implementation = subproject.getConfigurations().getByName(sourceSet.getImplementationConfigurationName());
                Configuration rewriteImplementation = subproject.getConfigurations().maybeCreate("rewrite" + sourceSet.getImplementationConfigurationName());
                rewriteImplementation.extendsFrom(implementation);

                // The implementation configuration doesn't include build/source directories from project dependencies
                // So mash it and our rewriteImplementation together to get everything
                List<Path> dependencyPaths = Stream.concat(rewriteImplementation.resolve().stream(), sourceSet.getCompileClasspath().getFiles().stream())
                        .map(File::toPath)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .distinct()
                        .collect(toList());

                JavaSourceSet sourceSetProvenance = null;
                if(javaPaths.size() > 0) {
                    JavaParser jp = JavaParser.fromJavaVersion()
                            .styles(styles)
                            .classpath(dependencyPaths)
                            .logCompilationWarningsAndErrors(extension.getLogCompilationWarningsAndErrors())
                            .build();
                    jp.setSourceSet(sourceSet.getName());
                    sourceFiles.addAll(map(jp.parse(javaPaths, baseDir, ctx), addProvenance(projectProvenance, null)));
                    sourceSetProvenance = jp.getSourceSet(ctx); // Hold onto provenance to apply it to resource files
                }

                for (File resourcesDir : sourceSet.getResources().getSourceDirectories()) {
                    if(resourcesDir.exists()) {
                        if(sourceSetProvenance == null) {
                            // Just in case there are no java source files, but there _are_ resource files
                            // Skip providing a classpath because it's time-consuming and non-Java sources have no concept of java type information
                            sourceSetProvenance = new JavaSourceSet(randomId(), sourceSet.getName(), emptyList());
                        }
                        sourceFiles.addAll(map(rp.parse(baseDir, resourcesDir.toPath(), alreadyParsed, ctx), addProvenance(projectProvenance, sourceSetProvenance)));
                    }
                }
            }

            //Collect any additional yaml/properties/xml files that are NOT already in a source set.
            sourceFiles.addAll(map(rp.parse(baseDir, subproject.getProjectDir().toPath(), alreadyParsed, ctx), addProvenance(projectProvenance, null)));

            return sourceFiles;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<NamedStyles> getStyles() {
        if(styles == null) {
            styles = environment().activateStyles(getActiveStyles());
            File checkstyleConfig = extension.getCheckstyleConfigFile();
            if (checkstyleConfig != null && checkstyleConfig.exists()) {
                try {
                    styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(checkstyleConfig.toPath(), extension.getCheckstyleProperties()));
                } catch (Exception e) {
                    logger.warn("Unable to parse checkstyle configuration", e);
                }
            }
        }
        return styles;
    }

    @SuppressWarnings("unused")
    public ResultsContainer listResults(boolean useAstCache) {
        Environment env = environment();
        Recipe recipe = env.activateRecipes(getActiveRecipes());

        logger.lifecycle("Validating active recipes");
        Collection<Validated> validated = recipe.validateAll();
        List<Validated.Invalid> failedValidations = validated.stream().map(Validated::failures)
                .flatMap(Collection::stream).collect(toList());
        if (!failedValidations.isEmpty()) {
            failedValidations.forEach(failedValidation -> logger.error(
                    "Recipe validation error in " + failedValidation.getProperty() + ": " +
                            failedValidation.getMessage(), failedValidation.getException()));
            if (extension.getFailOnInvalidActiveRecipes()) {
                throw new RuntimeException("Recipe validation errors detected as part of one or more activeRecipe(s). Please check error logs.");
            } else {
                logger.error("Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless.");
            }
        }

        List<SourceFile> sourceFiles;
        if(useAstCache && astCache.containsKey(rootProject.getProjectDir().toPath().toString())) {
            logger.lifecycle("Using cached in-memory ASTs");
            //noinspection unchecked
            sourceFiles = (List<SourceFile>) astCache.get(rootProject.getProjectDir().toPath().toString());
        } else {
            sourceFiles = parse();
            if(useAstCache) {
                astCache.put(rootProject.getProjectDir().toPath().toString(), sourceFiles);
            }
        }
        logger.lifecycle("All sources parsed, running active recipes: {}", String.join(", ", getActiveRecipes()));
        List<Result> results = recipe.run(sourceFiles);
        return new ResultsContainer(baseDir, results);
    }

    public void clearAstCache() {
        astCache.clear();
    }

    @Override
    public void shutdownRewrite() {
        J.clearCaches();
        Git.shutdown();
    }

    private <T extends SourceFile> UnaryOperator<T> addProvenance(List<Marker> projectProvenance, @Nullable Marker sourceSet) {
        return s -> {
            Markers m = s.getMarkers();
            for(Marker marker : projectProvenance) {
                m = m.add(marker);
            }
            if(sourceSet != null) {
                m = m.add(sourceSet);
            }
            return s.withMarkers(m);
        };
    }

    private void logRecipesThatMadeChanges(org.openrewrite.Result result) {
        for (Recipe recipe : result.getRecipesThatMadeChanges()) {
            logger.warn("    " + recipe.getName());
        }
    }
}

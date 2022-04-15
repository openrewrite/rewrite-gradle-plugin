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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.jvm.JvmHeapPressureMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.openrewrite.*;
import org.openrewrite.binary.Binary;
import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.gradle.DefaultRewriteExtension;
import org.openrewrite.gradle.GradleParser;
import org.openrewrite.gradle.GradleProjectParser;
import org.openrewrite.gradle.RewriteExtension;
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.java.style.*;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.BuildTool;
import org.openrewrite.marker.GitProvenance;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.ci.BuildEnvironment;
import org.openrewrite.shaded.jgit.api.Git;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.tree.ParsingExecutionContextView;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.stream.Collectors.*;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.gradle.TimeUtils.prettyPrint;
import static org.openrewrite.internal.ListUtils.map;

@SuppressWarnings("unused")
public class DefaultProjectParser implements GradleProjectParser {
    private final Logger logger = Logging.getLogger(DefaultProjectParser.class);
    protected final Path baseDir;
    protected final RewriteExtension extension;
    protected final Project project;
    private final List<Marker> sharedProvenance;
    private final Map<String, Object> astCache;

    private List<NamedStyles> styles;
    private Environment environment;

    public DefaultProjectParser(Project project, RewriteExtension extension, Map<String, Object> astCache) {
        this.baseDir = project.getProjectDir().toPath();
        this.extension = extension;
        this.project = project;
        this.astCache = astCache;

        BuildEnvironment buildEnvironment = BuildEnvironment.build(System::getenv);
        sharedProvenance = Stream.of(
                        buildEnvironment,
                        gitProvenance(baseDir, buildEnvironment),
                        new BuildTool(randomId(), BuildTool.Type.Gradle, project.getGradle().getGradleVersion()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Nullable
    private GitProvenance gitProvenance(Path baseDir, @Nullable BuildEnvironment buildEnvironment) {
        try {
            return GitProvenance.fromProjectDirectory(baseDir, buildEnvironment);
        } catch (Exception e) {
            // Logging at a low level as this is unlikely to happen except in non-git projects, where it is expected
            logger.debug("Unable to determine git provenance", e);
        }
        return null;
    }

    public SortedSet<String> getActiveRecipes() {
        String activeRecipeProp = System.getProperty("activeRecipe");
        if (activeRecipeProp == null) {
            return new TreeSet<>(extension.getActiveRecipes());
        } else {
            return new TreeSet<>(singleton(activeRecipeProp));
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
        ResourceParser rp = new ResourceParser(project, extension);
        Set<Path> result = new TreeSet<>(rp.listSources(baseDir, project.getProjectDir().toPath()));
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
        return result;
    }

    @Override
    public void dryRun(Path reportPath, boolean dumpGcActivity, boolean useAstCache, Consumer<Throwable> onError) {
        ParsingExecutionContextView ctx = ParsingExecutionContextView.view(new InMemoryExecutionContext(onError));
        if (dumpGcActivity) {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            new JvmHeapPressureMetrics().bindTo(meterRegistry);
            new JvmMemoryMetrics().bindTo(meterRegistry);

            File rewriteBuildDir = new File(project.getBuildDir(), "/rewrite");
            if (rewriteBuildDir.exists() || rewriteBuildDir.mkdirs()) {
                File rewriteGcLog = new File(rewriteBuildDir, "rewrite-gc.csv");
                try (FileOutputStream fos = new FileOutputStream(rewriteGcLog, false);
                     BufferedWriter logWriter = new BufferedWriter(new PrintWriter(fos))) {
                    logWriter.write("file,jvm.gc.overhead,g1.old.gen.size\n");
                    ctx.setParsingListener((input, sourceFile) -> {
                        try {
                            logWriter.write(input.getPath() + ",");
                            logWriter.write(meterRegistry.get("jvm.gc.overhead").gauge().value() + ",");
                            Gauge g1Used = meterRegistry.find("jvm.memory.used").tag("id", "G1 Old Gen").gauge();
                            logWriter.write((g1Used == null ? "" : Double.toString(g1Used.value())) + "\n");
                        } catch (IOException e) {
                            logger.error("Unable to write rewrite GC log");
                            throw new UncheckedIOException(e);
                        }
                    });
                    dryRun(reportPath, listResults(useAstCache, ctx));
                    logWriter.flush();
                    logger.lifecycle("Wrote rewrite GC log: {}", rewriteGcLog.getAbsolutePath());
                } catch (IOException e) {
                    logger.error("Unable to write rewrite GC log", e);
                    throw new UncheckedIOException(e);
                }
            }
        } else {
            dryRun(reportPath, listResults(useAstCache, ctx));
        }
    }

    public void dryRun(Path reportPath, ResultsContainer results) {
        try {
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

                if (project.getExtensions().getByType(DefaultRewriteExtension.class).getFailOnDryRunResults()) {
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
    public void run(boolean useAstCache, Consumer<Throwable> onError) {
        run(listResults(useAstCache, new InMemoryExecutionContext(onError)));
    }

    public void run(ResultsContainer results) {
        try {
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
                        writeAfter(results.getProjectRoot(), result);
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
                        File originalParentDir = originalLocation.toFile().getParentFile();

                        // On Mac this can return "false" even when the file was deleted, so skip the check
                        //noinspection ResultOfMethodCallIgnored
                        originalLocation.toFile().delete();

                        assert result.getAfter() != null;
                        // Ensure directories exist in case something was moved into a hitherto non-existent package
                        Path afterLocation = results.getProjectRoot().resolve(result.getAfter().getSourcePath());
                        File afterParentDir = afterLocation.toFile().getParentFile();
                        // Rename the directory if its name case has been changed, e.g. camel case to lower case.
                        if (afterParentDir.exists()
                                && afterParentDir.getAbsolutePath().equalsIgnoreCase((originalParentDir.getAbsolutePath()))
                                && !afterParentDir.getAbsolutePath().equals(originalParentDir.getAbsolutePath())) {
                            if (!originalParentDir.renameTo(afterParentDir)) {
                                throw new RuntimeException("Unable to rename directory from " + originalParentDir.getAbsolutePath() + " To: " + afterParentDir.getAbsolutePath());
                            }
                        } else if (!afterParentDir.exists() && !afterParentDir.mkdirs()) {
                            throw new RuntimeException("Unable to create directory " + afterParentDir.getAbsolutePath());
                        }
                        writeAfter(results.getProjectRoot(), result);
                    }
                    for (Result result : results.refactoredInPlace) {
                        writeAfter(results.getProjectRoot(), result);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to rewrite source files", e);
                }
            }
        } finally {
            shutdownRewrite();
        }
    }

    private static void writeAfter(Path root, Result result) {
        assert result.getAfter() != null;
        if(result.getAfter() instanceof Binary) {
            try (FileOutputStream sourceFileWriter = new FileOutputStream(
                    root.resolve(result.getAfter().getSourcePath()).toFile())) {
                sourceFileWriter.write(((Binary) result.getAfter()).getBytes());
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        } else {
            try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                    root.resolve(result.getAfter().getSourcePath()))) {
                sourceFileWriter.write(result.getAfter().printAll());
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        }
    }

    protected Environment environment() {
        if (environment == null) {
            Map<Object, Object> gradleProps = project.getProperties().entrySet().stream()
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

    public List<SourceFile> parse(ExecutionContext ctx) {
        List<SourceFile> sourceFiles = new ArrayList<>();
        Set<Path> alreadyParsed = new HashSet<>();
        if (project == project.getRootProject()) {
            for (Project subProject : project.getSubprojects()) {
                sourceFiles.addAll(parse(subProject, alreadyParsed, ctx));
            }
        }
        sourceFiles.addAll(parse(project, alreadyParsed, ctx));

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
            if (javaConvention == null) {
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

            ResourceParser rp = new ResourceParser(project, extension);

            List<SourceFile> sourceFiles = new ArrayList<>();
            JavaTypeCache javaTypeCache = new JavaTypeCache();
            for (SourceSet sourceSet : sourceSets) {
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
                if (javaPaths.size() > 0) {
                    logger.info("Parsing {} Java sources from {}/{}", javaPaths.size(), project.getName(), sourceSet.getName());
                    JavaParser jp = JavaParser.fromJavaVersion()
                            .styles(styles)
                            .classpath(dependencyPaths)
                            .typeCache(javaTypeCache)
                            .logCompilationWarningsAndErrors(extension.getLogCompilationWarningsAndErrors())
                            .build();
                    jp.setSourceSet(sourceSet.getName());
                    Instant start = Instant.now();
                    List<J.CompilationUnit> cus = jp.parse(javaPaths, baseDir, ctx);
                    Duration parseDuration = Duration.between(start, Instant.now());
                    logger.info("Finished parsing Java sources from {}/{} in {} ({} per source)",
                            project.getName(), sourceSet.getName(), prettyPrint(parseDuration), prettyPrint(parseDuration.dividedBy(javaPaths.size())));
                    sourceFiles.addAll(map(maybeAutodetectStyles(cus, styles), addProvenance(projectProvenance, null)));
                    sourceSetProvenance = jp.getSourceSet(ctx); // Hold onto provenance to apply it to resource files
                }

                for (File resourcesDir : sourceSet.getResources().getSourceDirectories()) {
                    if (resourcesDir.exists()) {
                        if (sourceSetProvenance == null) {
                            // Just in case there are no java source files, but there _are_ resource files
                            // Skip providing a classpath because it's time-consuming and non-Java sources have no concept of java type information
                            sourceSetProvenance = new JavaSourceSet(randomId(), sourceSet.getName(), emptyList());
                        }
                        sourceFiles.addAll(map(rp.parse(baseDir, resourcesDir.toPath(), alreadyParsed, ctx), addProvenance(projectProvenance, sourceSetProvenance)));
                    }
                }
            }

            if (extension.isEnableExperimentalGradleBuildScriptParsing()) {
                logger.warn("Rewrite of Gradle files is an incubating feature which has been disabled in this release because it needs a bit more time to bake.");
                File buildScriptFile = subproject.getBuildFile();
                try {
                    if (buildScriptFile.toString().toLowerCase().endsWith(".gradle") && buildScriptFile.exists()) {
                        GradleParser gradleParser = new GradleParser(
                                GroovyParser.builder()
                                        .styles(styles)
                                        .typeCache(javaTypeCache)
                                        .logCompilationWarningsAndErrors(true));

                        sourceFiles.addAll(gradleParser.parse(singleton(buildScriptFile.toPath()), baseDir, ctx));
                    }
                } catch (Exception e) {
                    logger.warn("Problem with parsing gradle script at \"" + buildScriptFile.getAbsolutePath() + "\" : ", e);
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
        if (styles == null) {
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
    public ResultsContainer listResults(boolean useAstCache, ExecutionContext ctx) {
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
        if (useAstCache && astCache.containsKey(project.getProjectDir().toPath().toString())) {
            logger.lifecycle("Using cached in-memory ASTs");
            //noinspection unchecked
            sourceFiles = (List<SourceFile>) astCache.get(project.getProjectDir().toPath().toString());
        } else {
            sourceFiles = parse(ctx);
            if (useAstCache) {
                astCache.put(project.getProjectDir().toPath().toString(), sourceFiles);
            }
        }
        logger.lifecycle("All sources parsed, running active recipes: {}", String.join(", ", getActiveRecipes()));
        List<Result> results = recipe.run(sourceFiles, ctx);
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

    private List<J.CompilationUnit> maybeAutodetectStyles(List<J.CompilationUnit> sourceFiles, @Nullable Collection<NamedStyles> styles) {
        if (styles != null) {
            return sourceFiles;
        }

        Autodetect autodetect = Autodetect.detect(sourceFiles);

        Collection<NamedStyles> namedStyles;
        namedStyles = Collections.singletonList(autodetect);

        ImportLayoutStyle importLayout = NamedStyles.merge(ImportLayoutStyle.class, namedStyles);
        logger.debug("Auto-detected import layout:\n{}", importLayout);

        SpacesStyle spacesStyle = NamedStyles.merge(SpacesStyle.class, namedStyles);
        logger.debug("Auto-detected spaces style:\n{}", spacesStyle);

        TabsAndIndentsStyle tabsStyle = NamedStyles.merge(TabsAndIndentsStyle.class, namedStyles);
        logger.debug("Auto-detected tabs and indents:\n{}", tabsStyle);

        return map(sourceFiles, cu -> {
            List<Marker> markers = ListUtils.concat(map(cu.getMarkers().getMarkers(), m -> m instanceof NamedStyles ? null : m), autodetect);
            return cu.withMarkers(cu.getMarkers().withMarkers(markers));
        });
    }

    private <T extends SourceFile> UnaryOperator<T> addProvenance(List<Marker> projectProvenance, @Nullable Marker sourceSet) {
        return s -> {
            Markers m = s.getMarkers();
            for (Marker marker : projectProvenance) {
                m = m.add(marker);
            }
            if (sourceSet != null) {
                m = m.add(sourceSet);
            }
            return s.withMarkers(m);
        };
    }

    protected void logRecipesThatMadeChanges(Result result) {
        String indent = "    ";
        String prefix = "    ";
        for (RecipeDescriptor recipeDescriptor : result.getRecipeDescriptorsThatMadeChanges()) {
            logRecipe(recipeDescriptor, prefix);
            prefix = prefix + indent;
        }
    }

    private void logRecipe(RecipeDescriptor rd, String prefix) {
        StringBuilder recipeString = new StringBuilder(prefix + rd.getName());
        if (!rd.getOptions().isEmpty()) {
            String opts = rd.getOptions().stream().map(option -> {
                        if (option.getValue() != null) {
                            return option.getName() + "=" + option.getValue();
                        }
                        return null;
                    }
            ).filter(Objects::nonNull).collect(joining(", "));
            if (!opts.isEmpty()) {
                recipeString.append(": {").append(opts).append("}");
            }
        }
        logger.warn(recipeString.toString());
        for (RecipeDescriptor rchild : rd.getRecipeList()) {
            logRecipe(rchild, prefix + "    ");
        }
    }
}

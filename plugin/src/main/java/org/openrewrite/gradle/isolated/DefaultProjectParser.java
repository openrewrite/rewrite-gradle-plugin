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
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.initialization.Settings;
import org.gradle.api.internal.tasks.userinput.UserInputHandler;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.GroovyPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.invocation.DefaultGradle;
import org.gradle.util.GradleVersion;
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet;
import org.openrewrite.*;
import org.openrewrite.binary.Binary;
import org.openrewrite.config.Environment;
import org.openrewrite.config.OptionDescriptor;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.gradle.*;
import org.openrewrite.gradle.isolated.ui.RecipeDescriptorTreePrompter;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.gradle.marker.GradleProjectBuilder;
import org.openrewrite.gradle.marker.GradleSettings;
import org.openrewrite.gradle.marker.GradleSettingsBuilder;
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.java.style.CheckstyleConfigLoader;
import org.openrewrite.java.tree.J;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.*;
import org.openrewrite.marker.ci.BuildEnvironment;
import org.openrewrite.polyglot.*;
import org.openrewrite.quark.Quark;
import org.openrewrite.quark.QuarkParser;
import org.openrewrite.remote.Remote;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.text.PlainTextParser;
import org.openrewrite.tree.ParseError;
import org.openrewrite.tree.ParsingEventListener;
import org.openrewrite.tree.ParsingExecutionContextView;
import org.openrewrite.xml.tree.Xml;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.*;
import static java.util.stream.Collectors.*;
import static org.openrewrite.PathUtils.separatorsToUnix;
import static org.openrewrite.Tree.randomId;

@SuppressWarnings("unused")
public class DefaultProjectParser implements GradleProjectParser {
    private static final String LOG_INDENT_INCREMENT = "    ";

    private final Logger logger = Logging.getLogger(DefaultProjectParser.class);
    private final AtomicBoolean firstWarningLogged = new AtomicBoolean(false);
    protected final Path baseDir;
    protected final RewriteExtension extension;
    protected final Project project;
    private final List<Marker> sharedProvenance;

    private List<NamedStyles> styles;
    private Environment environment;

    public DefaultProjectParser(Project project, RewriteExtension extension) {
        this.baseDir = repositoryRoot(project);
        this.extension = extension;
        this.project = project;

        BuildEnvironment buildEnvironment = BuildEnvironment.build(System::getenv);
        sharedProvenance = Stream.of(
                        buildEnvironment,
                        gitProvenance(baseDir, buildEnvironment),
                        OperatingSystemProvenance.current(),
                        new BuildTool(randomId(), BuildTool.Type.Gradle, project.getGradle().getGradleVersion()))
                .filter(Objects::nonNull)
                .collect(toList());
    }

    /**
     * Attempt to determine the root of the git repository for the given project.
     * Many Gradle builds co-locate the build root with the git repository root, but that is not required.
     * If no git repository can be located in any folder containing the build, the build root will be returned.
     */
    static Path repositoryRoot(Project project) {
        Path buildRoot = project.getRootProject().getProjectDir().toPath();
        Path maybeBaseDir = buildRoot;
        while (maybeBaseDir != null && !Files.exists(maybeBaseDir.resolve(".git"))) {
            maybeBaseDir = maybeBaseDir.getParent();
        }
        if (maybeBaseDir == null) {
            return buildRoot;
        }
        return maybeBaseDir;
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


    // By accident, we were inconsistent with the names of these properties between this and the maven plugin
    // Check all variants of the name, preferring more-fully-qualified names
    @Nullable
    private String getPropertyWithVariantNames(String property) {
        String maybeProp = System.getProperty("rewrite." + property + "s");
        if (maybeProp == null) {
            maybeProp = System.getProperty("rewrite." + property);
        }
        if (maybeProp == null) {
            maybeProp = System.getProperty(property + "s");
        }
        if (maybeProp == null) {
            maybeProp = System.getProperty(property);
        }
        return maybeProp;
    }

    public List<String> getActiveRecipes() {
        String activeRecipe = getPropertyWithVariantNames("activeRecipe");
        if (activeRecipe == null) {
            return new ArrayList<>(extension.getActiveRecipes());
        }
        return Arrays.asList(activeRecipe.split(","));
    }

    public List<String> getActiveStyles() {
        String activeStyle = getPropertyWithVariantNames("activeStyle");
        if (activeStyle == null) {
            return new ArrayList<>(extension.getActiveStyles());
        }
        return Arrays.asList(activeStyle.split(","));
    }

    public List<String> getAvailableStyles() {
        return environment().listStyles().stream().map(NamedStyles::getName).collect(toList());
    }

    public void discoverRecipes(boolean interactive, ServiceRegistry serviceRegistry) {
        Collection<RecipeDescriptor> availableRecipeDescriptors = this.listRecipeDescriptors();

        if (interactive) {
            logger.quiet("Entering interactive mode, Ctrl-C to exit...");
            UserInputHandler prompter = serviceRegistry.get(UserInputHandler.class);
            RecipeDescriptorTreePrompter treePrompter = new RecipeDescriptorTreePrompter(prompter);
            RecipeDescriptor rd = treePrompter.execute(availableRecipeDescriptors);
            writeRecipeDescriptor(rd);
        } else {
            List<String> activeRecipes = getActiveRecipes();
            List<String> availableStyles = getAvailableStyles();
            List<String> activeStyles = getActiveStyles();

            logger.quiet("Available Recipes:");
            for (RecipeDescriptor recipe : availableRecipeDescriptors) {
                logger.quiet(indent(1, recipe.getName()));
            }

            logger.quiet(indent(0, ""));
            logger.quiet("Available Styles:");
            for (String style : availableStyles) {
                logger.quiet(indent(1, style));
            }

            logger.quiet(indent(0, ""));
            logger.quiet("Active Styles:");
            for (String style : activeStyles) {
                logger.quiet(indent(1, style));
            }

            logger.quiet(indent(0, ""));
            logger.quiet("Active Recipes:");
            for (String activeRecipe : activeRecipes) {
                logger.quiet(indent(1, activeRecipe));
            }

            logger.quiet(indent(0, ""));
            logger.quiet("Found " + availableRecipeDescriptors.size() + " available recipes and " + availableStyles.size() + " available styles.");
            logger.quiet("Configured with " + activeRecipes.size() + " active recipes and " + activeStyles.size() + " active styles.");
        }
    }

    public Collection<RecipeDescriptor> listRecipeDescriptors() {
        return environment().listRecipeDescriptors();
    }

    @SuppressWarnings("ConstantConditions")
    private void writeRecipeDescriptor(RecipeDescriptor rd) {
        logger.quiet(indent(0, rd.getDisplayName()));
        logger.quiet(indent(1, rd.getName()));
        if (rd.getDescription() != null && !rd.getDescription().isEmpty()) {
            logger.quiet(indent(1, rd.getDescription()));
        }
        if (!rd.getOptions().isEmpty()) {
            logger.quiet(indent(0, "options: "));
            for (OptionDescriptor od : rd.getOptions()) {
                logger.quiet(indent(1, od.getName() + ": " + od.getType() + (od.isRequired() ? "!" : "")));
                if (od.getDescription() != null && !od.getDescription().isEmpty()) {
                    logger.quiet(indent(2, od.getDescription()));
                }
            }
        }
        logger.quiet("");
    }

    private static String indent(int indent, CharSequence content) {
        StringBuilder prefix = repeat(indent);
        return prefix.append(content).toString();
    }

    private static StringBuilder repeat(int repeat) {
        StringBuilder buffer = new StringBuilder(repeat * LOG_INDENT_INCREMENT.length());
        for (int i = 0; i < repeat; i++) {
            buffer.append(LOG_INDENT_INCREMENT);
        }
        return buffer;
    }

    public Collection<Path> listSources() {
        // Use a sorted collection so that gradle input detection isn't thrown off by ordering
        Set<Path> result = new TreeSet<>(omniParser(emptySet()).acceptedPaths(baseDir, project.getProjectDir().toPath()));
        //noinspection deprecation
        JavaPluginConvention javaConvention = project.getConvention().findPlugin(JavaPluginConvention.class);
        if (javaConvention != null) {
            for (SourceSet sourceSet : javaConvention.getSourceSets()) {
                sourceSet.getAllSource().getFiles().stream()
                        .map(File::toPath)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .forEach(result::add);
            }
        }
        return result;
    }

    public void dryRun(Path reportPath, boolean dumpGcActivity, Consumer<Throwable> onError) {
        ParsingExecutionContextView ctx = ParsingExecutionContextView.view(new InMemoryExecutionContext(onError));
        if (dumpGcActivity) {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            try (JvmHeapPressureMetrics heapMetrics = new JvmHeapPressureMetrics()) {
                heapMetrics.bindTo(meterRegistry);
                new JvmMemoryMetrics().bindTo(meterRegistry);

                //noinspection deprecation
                File rewriteBuildDir = new File(project.getBuildDir(), "/rewrite");
                if (rewriteBuildDir.exists() || rewriteBuildDir.mkdirs()) {
                    File rewriteGcLog = new File(rewriteBuildDir, "rewrite-gc.csv");
                    try (FileOutputStream fos = new FileOutputStream(rewriteGcLog, false);
                         BufferedWriter logWriter = new BufferedWriter(new PrintWriter(fos))) {
                        logWriter.write("file,jvm.gc.overhead,g1.old.gen.size\n");
                        ctx.setParsingListener(new ParsingEventListener() {
                            @Override
                            public void parsed(Parser.Input input, SourceFile sourceFile) {
                                try {
                                    logWriter.write(input.getPath() + ",");
                                    logWriter.write(meterRegistry.get("jvm.gc.overhead").gauge().value() + ",");
                                    Gauge g1Used = meterRegistry.find("jvm.memory.used").tag("id", "G1 Old Gen").gauge();
                                    logWriter.write((g1Used == null ? "" : Double.toString(g1Used.value())) + "\n");
                                } catch (IOException e) {
                                    logger.error("Unable to write rewrite GC log");
                                    throw new UncheckedIOException(e);
                                }
                            }
                        });
                        dryRun(reportPath, listResults(ctx));
                        logWriter.flush();
                        logger.lifecycle("Wrote rewrite GC log: {}", rewriteGcLog.getAbsolutePath());
                    } catch (IOException e) {
                        logger.error("Unable to write rewrite GC log", e);
                        throw new UncheckedIOException(e);
                    }
                }
            }
        } else {
            dryRun(reportPath, listResults(ctx));
        }
    }

    public void dryRun(Path reportPath, ResultsContainer results) {
        try {
            RuntimeException firstException = results.getFirstException();
            if (firstException != null) {
                logger.error("The recipe produced an error. Please report this to the recipe author.");
                throw firstException;
            }

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
                    logger.warn("These recipes would make changes to {}:", result.getBefore().getSourcePath());
                    logRecipesThatMadeChanges(result);
                }

                //noinspection ResultOfMethodCallIgnored
                reportPath.getParent().toFile().mkdirs();
                try (BufferedWriter writer = Files.newBufferedWriter(reportPath)) {
                    Stream.concat(
                                    Stream.concat(results.generated.stream(), results.deleted.stream()),
                                    Stream.concat(results.moved.stream(), results.refactoredInPlace.stream())
                            )
                            // cannot meaningfully display diffs of these things. Console output notes that they were touched by a recipe.
                            .filter(it -> !(it.getAfter() instanceof Binary) && !(it.getAfter() instanceof Quark))
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
                logger.warn("    {}", reportPath.normalize());
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

    public void run(Consumer<Throwable> onError) {
        ExecutionContext ctx = new InMemoryExecutionContext(onError);
        run(listResults(ctx), ctx);
    }

    public void run(ResultsContainer results, ExecutionContext ctx) {
        try {
            if (results.isNotEmpty()) {
                RuntimeException firstException = results.getFirstException();
                if (firstException != null) {
                    logger.error("The recipe produced an error. Please report this to the recipe author.");
                    throw firstException;
                }

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
                        writeAfter(results.getProjectRoot(), result, ctx);
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

                        assert result.getAfter() != null;
                        // Ensure directories exist in case something was moved into a hitherto nonexistent package
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
                        if (result.getAfter() instanceof Quark) {
                            // We don't know the contents of a Quark, but we can move it
                            Files.move(originalLocation, results.getProjectRoot().resolve(result.getAfter().getSourcePath()));
                        } else {
                            // On Mac this can return "false" even when the file was deleted, so skip the check
                            //noinspection ResultOfMethodCallIgnored
                            originalLocation.toFile().delete();
                            writeAfter(results.getProjectRoot(), result, ctx);
                        }
                    }

                    for (Result result : results.refactoredInPlace) {
                        writeAfter(results.getProjectRoot(), result, ctx);
                    }
                    List<Path> emptyDirectories = results.newlyEmptyDirectories();
                    if (!emptyDirectories.isEmpty()) {
                        logger.quiet("Removing {} newly empty directories:",
                                emptyDirectories.size());
                        for (Path emptyDirectory : emptyDirectories) {
                            logger.quiet("  {}", emptyDirectory);
                            Files.delete(emptyDirectory);
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to rewrite source files", e);
                }
            }
        } finally {
            shutdownRewrite();
        }
    }

    private static void writeAfter(Path root, Result result, ExecutionContext ctx) {
        assert result.getAfter() != null;
        Path targetPath = root.resolve(result.getAfter().getSourcePath());
        File targetFile = targetPath.toFile();
        if (!targetFile.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetFile.getParentFile().mkdirs();
        }
        if (result.getAfter() instanceof Binary) {
            try (FileOutputStream sourceFileWriter = new FileOutputStream(targetFile)) {
                sourceFileWriter.write(((Binary) result.getAfter()).getBytes());
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        } else if (result.getAfter() instanceof Remote) {
            Remote remote = (Remote) result.getAfter();
            try (FileOutputStream sourceFileWriter = new FileOutputStream(targetFile)) {
                InputStream source = remote.getInputStream(ctx);
                byte[] buf = new byte[4096];
                int length;
                while ((length = source.read(buf)) > 0) {
                    sourceFileWriter.write(buf, 0, length);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        } else //noinspection StatementWithEmptyBody
            if (result.getAfter() instanceof Quark) {
                // Don't attempt to write to a Quark; it has already been logged as change that has been made
            } else {
                Charset charset = result.getAfter().getCharset() == null ? StandardCharsets.UTF_8 : result.getAfter().getCharset();
                try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(targetPath, charset)) {
                    sourceFileWriter.write(result.getAfter().printAll(new PrintOutputCapture<>(0, new SanitizedMarkerPrinter())));
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to rewrite source files", e);
                }
            }
        if (result.getAfter().getFileAttributes() != null) {
            FileAttributes fileAttributes = result.getAfter().getFileAttributes();
            if (targetFile.canRead() != fileAttributes.isReadable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setReadable(fileAttributes.isReadable());
            }
            if (targetFile.canWrite() != fileAttributes.isWritable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setWritable(fileAttributes.isWritable());
            }
            if (targetFile.canExecute() != fileAttributes.isExecutable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setExecutable(fileAttributes.isExecutable());
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
                logger.warn("Rewrite configuration file {} does not exist.", rewriteConfig);
            }

            environment = env.build();
        }
        return environment;
    }

    public Stream<SourceFile> parse(ExecutionContext ctx) {
        Stream<SourceFile> builder = Stream.of();
        Set<Path> alreadyParsed = new HashSet<>();
        if (project == project.getRootProject()) {
            for (Project subProject : project.getSubprojects()) {
                builder = Stream.concat(builder, parse(subProject, alreadyParsed, ctx));
            }
        }
        builder = Stream.concat(builder, parse(project, alreadyParsed, ctx));

        // log parse errors here at the end, so that we don't log parse errors for files that were excluded
        return builder.map(this::logParseErrors);
    }

    public Stream<SourceFile> parse(Project subproject, Set<Path> alreadyParsed, ExecutionContext ctx) {
        String cliPort = System.getenv("MODERNE_CLI_PORT");
        try (ProgressBar progressBar = StringUtils.isBlank(cliPort) ? new NoopProgressBar() :
                new RemoteProgressBarSender(Integer.parseInt(cliPort))) {
            SourceFileStream sourceFileStream = SourceFileStream.build(
                    subproject.getPath(),
                    projectName -> progressBar.intermediateResult(":" + projectName)
            );

            Collection<PathMatcher> exclusions = extension.getExclusions().stream()
                    .map(pattern -> subproject.getProjectDir().toPath().getFileSystem().getPathMatcher("glob:" + pattern))
                    .collect(toList());
            if (isExcluded(exclusions, subproject.getProjectDir().toPath())) {
                logger.lifecycle("Skipping project {} because it is excluded", subproject.getPath());
                return Stream.empty();
            }

            logger.lifecycle("Scanning sources in project {}", subproject.getPath());
            List<NamedStyles> styles = getStyles();
            logger.lifecycle("Using active styles {}", styles.stream().map(NamedStyles::getName).collect(toList()));
            @SuppressWarnings("deprecation")
            JavaPluginConvention javaConvention = subproject.getConvention().findPlugin(JavaPluginConvention.class);
            List<SourceSet> sourceSets;
            List<Marker> projectProvenance;
            if (javaConvention == null) {
                projectProvenance = sharedProvenance;
                sourceSets = emptyList();
            } else {
                projectProvenance = new ArrayList<>(sharedProvenance);
                projectProvenance.add(new JavaProject(randomId(), subproject.getName(),
                        new JavaProject.Publication(subproject.getGroup().toString(),
                                subproject.getName(),
                                subproject.getVersion().toString())));
                sourceSets = javaConvention.getSourceSets().stream()
                        .sorted(Comparator.comparingInt(sourceSet -> {
                            if ("main".equals(sourceSet.getName())) {
                                return 0;
                            } else if ("test".equals(sourceSet.getName())) {
                                return 1;
                            } else {
                                return 2;
                            }
                        })).collect(toList());
            }

            if (subproject.getPlugins().hasPlugin("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension") ||
                subproject.getExtensions().findByName("kotlin") != null && subproject.getExtensions().getByName("kotlin").getClass()
                        .getCanonicalName().startsWith("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension")) {
                sourceFileStream = sourceFileStream.concat(parseMultiplatformKotlinProject(subproject, exclusions, alreadyParsed, ctx));
            }

            Set<String> sourceDirs = new HashSet<>();
            for (SourceSet sourceSet : sourceSets) {
                Stream<SourceFile> sourceSetSourceFiles = Stream.of();
                int sourceSetSize = 0;

                JavaTypeCache javaTypeCache = new JavaTypeCache();
                JavaCompile javaCompileTask = (JavaCompile) subproject.getTasks().getByName(sourceSet.getCompileJavaTaskName());
                JavaVersion javaVersion = new JavaVersion(randomId(), System.getProperty("java.runtime.version"),
                        System.getProperty("java.vm.vendor"),
                        javaCompileTask.getSourceCompatibility(),
                        javaCompileTask.getTargetCompatibility());

                List<Path> unparsedSources = sourceSet.getAllSource()
                        .getSourceDirectories()
                        .filter(it -> it.exists() && !alreadyParsed.contains(it.toPath()))
                        .getFiles()
                        .stream()
                        .flatMap(sourceDir -> {
                            try {
                                return Files.walk(sourceDir.toPath());
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        })
                        .filter(Files::isRegularFile)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .distinct()
                        .collect(Collectors.toList());
                List<Path> javaPaths = unparsedSources.stream()
                        .filter(it -> it.toString().endsWith(".java") && !alreadyParsed.contains(it))
                        .collect(toList());

                // classpath doesn't include the transitive dependencies of the implementation configuration
                // These aren't needed for compilation, but we want them so recipes have access to comprehensive type information
                // The implementation configuration isn't resolvable, so we need a new configuration that extends from it
                Configuration implementation = subproject.getConfigurations().getByName(sourceSet.getImplementationConfigurationName());
                Configuration rewriteImplementation = subproject.getConfigurations().maybeCreate("rewrite" + sourceSet.getImplementationConfigurationName());
                rewriteImplementation.extendsFrom(implementation);

                Set<File> implementationClasspath;
                try {
                    implementationClasspath = rewriteImplementation.resolve();
                } catch (Exception e) {
                    logger.warn("Failed to resolve dependencies from {}:{}. Some type information may be incomplete",
                            subproject.getPath(), sourceSet.getImplementationConfigurationName());
                    implementationClasspath = emptySet();
                }

                // The implementation configuration doesn't include build/source directories from project dependencies
                // So mash it and our rewriteImplementation together to get everything
                List<Path> dependencyPaths = Stream.concat(implementationClasspath.stream(), sourceSet.getCompileClasspath().getFiles().stream())
                        .map(File::toPath)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .distinct()
                        .collect(toList());

                if (!javaPaths.isEmpty()) {
                    alreadyParsed.addAll(javaPaths);
                    Stream<SourceFile> cus = Stream
                            .of((Supplier<JavaParser>) () -> JavaParser.fromJavaVersion()
                                    .classpath(dependencyPaths)
                                    .styles(styles)
                                    .typeCache(javaTypeCache)
                                    .logCompilationWarningsAndErrors(extension.getLogCompilationWarningsAndErrors())
                                    .build())
                            .map(Supplier::get)
                            .flatMap(jp -> jp.parse(javaPaths, baseDir, ctx))
                            .map(cu -> {
                                //noinspection deprecation
                                if (isExcluded(exclusions, cu.getSourcePath()) ||
                                    cu.getSourcePath().startsWith(baseDir.relativize(subproject.getBuildDir().toPath()))) {
                                    return null;
                                }
                                return cu;
                            })
                            .filter(Objects::nonNull)
                            .map(it -> it.withMarkers(it.getMarkers().add(javaVersion)));
                    sourceSetSourceFiles = Stream.concat(sourceSetSourceFiles, cus);
                    sourceSetSize += javaPaths.size();
                    logger.info("Scanned {} Java sources in {}/{}", javaPaths.size(), subproject.getPath(), sourceSet.getName());
                }

                if (subproject.getPlugins().hasPlugin("org.jetbrains.kotlin.jvm")) {
                    String excludedProtosPath = subproject.getProjectDir().getPath() + "/protos/build/generated";
                    List<Path> kotlinPaths = unparsedSources.stream()
                            .filter(it -> !it.toString().startsWith(excludedProtosPath))
                            .filter(it -> it.toString().endsWith(".kt"))
                            .collect(toList());

                    if (!kotlinPaths.isEmpty()) {
                        alreadyParsed.addAll(kotlinPaths);
                        Stream<SourceFile> cus = Stream
                                .of((Supplier<KotlinParser>) () -> KotlinParser.builder()
                                        .classpath(dependencyPaths)
                                        .styles(styles)
                                        .typeCache(javaTypeCache)
                                        .logCompilationWarningsAndErrors(extension.getLogCompilationWarningsAndErrors())
                                        .build())
                                .map(Supplier::get)
                                .flatMap(kp -> kp.parse(kotlinPaths, baseDir, ctx))
                                .map(cu -> {
                                    if (isExcluded(exclusions, cu.getSourcePath())) {
                                        return null;
                                    }
                                    return cu;
                                })
                                .filter(Objects::nonNull)
                                .map(it -> it.withMarkers(it.getMarkers().add(javaVersion)));
                        sourceSetSourceFiles = Stream.concat(sourceSetSourceFiles, cus);
                        sourceSetSize += kotlinPaths.size();
                        logger.info("Scanned {} Kotlin sources in {}/{}", kotlinPaths.size(), subproject.getPath(), sourceSet.getName());
                    }
                }
                if (subproject.getPlugins().hasPlugin(GroovyPlugin.class)) {
                    List<Path> groovyPaths = unparsedSources.stream()
                            .filter(it -> it.toString().endsWith(".groovy"))
                            .collect(toList());

                    if (!groovyPaths.isEmpty()) {
                        // Groovy sources are aware of java types that are intermixed in the same directory/sourceSet
                        // Include the build directory containing class files so these definitions are available
                        List<Path> dependenciesWithBuildDirs = Stream.concat(
                                dependencyPaths.stream(),
                                sourceSet.getOutput().getClassesDirs().getFiles().stream().map(File::toPath)
                        ).collect(toList());

                        alreadyParsed.addAll(groovyPaths);

                        Stream<SourceFile> cus = Stream
                                .of((Supplier<GroovyParser>) () -> GroovyParser.builder()
                                        .classpath(dependenciesWithBuildDirs)
                                        .styles(styles)
                                        .typeCache(javaTypeCache)
                                        .logCompilationWarningsAndErrors(false)
                                        .build())
                                .map(Supplier::get)
                                .flatMap(gp -> gp.parse(groovyPaths, baseDir, ctx))
                                .map(cu -> {
                                    if (isExcluded(exclusions, cu.getSourcePath())) {
                                        return null;
                                    }
                                    return cu;
                                })
                                .filter(Objects::nonNull)
                                .map(it -> it.withMarkers(it.getMarkers().add(javaVersion)));
                        sourceSetSourceFiles = Stream.concat(sourceSetSourceFiles, cus);
                        sourceSetSize += groovyPaths.size();
                        logger.info("Scanned {} Groovy sources in {}/{}", groovyPaths.size(), subproject.getPath(), sourceSet.getName());
                    }
                }

                for (File resourcesDir : sourceSet.getResources().getSourceDirectories()) {
                    if (resourcesDir.exists() && !alreadyParsed.contains(resourcesDir.toPath())) {
                        OmniParser omniParser = omniParser(alreadyParsed);
                        List<Path> accepted = omniParser.acceptedPaths(baseDir, resourcesDir.toPath());
                        sourceSetSourceFiles = Stream.concat(
                                sourceSetSourceFiles,
                                omniParser.parse(accepted, baseDir, new InMemoryExecutionContext())
                        );
                        alreadyParsed.addAll(accepted);
                        sourceSetSize += accepted.size();
                    }
                }

                JavaSourceSet sourceSetProvenance = JavaSourceSet.build(sourceSet.getName(), dependencyPaths, javaTypeCache, false);
                sourceFileStream = sourceFileStream.concat(sourceSetSourceFiles.map(addProvenance(sourceSetProvenance)), sourceSetSize);
                // Some source sets get misconfigured to have the same directories as other source sets
                // This causes duplicate source files to be parsed, so once a source set has been parsed exclude it from future parsing
                for (File file : sourceSet.getAllSource().getSourceDirectories().getFiles()) {
                    alreadyParsed.add(file.toPath());
                }
            }
            SourceFileStream gradleFiles = parseGradleFiles(exclusions, alreadyParsed, ctx);
            sourceFileStream = sourceFileStream.concat(gradleFiles, gradleFiles.size());

            SourceFileStream nonProjectResources = parseNonProjectResources(subproject, alreadyParsed, ctx, projectProvenance, sourceFileStream);
            sourceFileStream = sourceFileStream.concat(nonProjectResources, nonProjectResources.size());

            progressBar.setMax(sourceFileStream.size());
            return sourceFileStream
                    .map(addProvenance(projectProvenance))
                    .peek(it -> progressBar.step());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private GradleParser gradleParser() {
        List<Path> settingsClasspath;
        if (GradleVersion.current().compareTo(GradleVersion.version("4.4")) >= 0) {
            try {
                Settings settings = ((DefaultGradle) project.getGradle()).getSettings();
                settingsClasspath = settings.getBuildscript().getConfigurations().getByName("classpath").resolve()
                        .stream()
                        .map(File::toPath)
                        .collect(toList());
            } catch (IllegalStateException e) {
                settingsClasspath = emptyList();
            }
        } else {
            settingsClasspath = emptyList();
        }
        List<Path> buildscriptClasspath = project.getBuildscript().getConfigurations().getByName("classpath").resolve()
                .stream()
                .map(File::toPath)
                .collect(toList());

        return GradleParser.builder()
                .groovyParser(GroovyParser.builder()
                        .typeCache(new JavaTypeCache())
                        .styles(styles)
                        .logCompilationWarningsAndErrors(false))
                .buildscriptClasspath(buildscriptClasspath)
                .settingsClasspath(settingsClasspath)
                .build();
    }

    private SourceFileStream parseGradleFiles(
            Collection<PathMatcher> exclusions, Set<Path> alreadyParsed, ExecutionContext ctx) {
        Stream<SourceFile> sourceFiles = Stream.empty();
        int gradleFileCount = 0;

        GradleParser gradleParser = null;
        if (project.getBuildscript().getSourceFile() != null) {
            File buildGradleFile = project.getBuildscript().getSourceFile();
            Path buildScriptPath = baseDir.relativize(project.getBuildscript().getSourceFile().toPath());
            if (!isExcluded(exclusions, buildScriptPath) && buildGradleFile.exists()) {
                alreadyParsed.add(buildScriptPath);
                GradleProject gp = GradleProjectBuilder.gradleProject(project);
                if (buildScriptPath.toString().endsWith(".gradle")) {
                    gradleParser = gradleParser();
                    sourceFiles = gradleParser.parse(singleton(buildGradleFile.toPath()), baseDir, ctx);
                    gradleFileCount++;
                } else {
                    sourceFiles = PlainTextParser.builder().build()
                            .parse(singleton(buildGradleFile.toPath()), baseDir, ctx);
                    gradleFileCount++;
                }
                sourceFiles = sourceFiles.map(sourceFile -> sourceFile.withMarkers(sourceFile.getMarkers().add(gp)));
                alreadyParsed.add(project.getBuildscript().getSourceFile().toPath());
            }
        }

        if (project == project.getRootProject()) {
            File settingsGradleFile = project.file("settings.gradle");
            File settingsGradleKtsFile = project.file("settings.gradle.kts");
            GradleSettings gs = null;
            if (GradleVersion.current().compareTo(GradleVersion.version("4.4")) >= 0 && (settingsGradleFile.exists() || settingsGradleKtsFile.exists())) {
                gs = GradleSettingsBuilder.gradleSettings(((DefaultGradle) project.getGradle()).getSettings());
            }
            GradleSettings finalGs = gs;
            if (settingsGradleFile.exists()) {
                Path settingsPath = baseDir.relativize(settingsGradleFile.toPath());
                if (gradleParser == null) {
                    gradleParser = gradleParser();
                }
                sourceFiles = Stream.concat(
                        sourceFiles,
                        gradleParser
                                .parse(singleton(settingsGradleFile.toPath()), baseDir, ctx)
                                .map(sourceFile -> {
                                    if (finalGs == null) {
                                        return sourceFile;
                                    }
                                    return sourceFile.withMarkers(sourceFile.getMarkers().add(finalGs));
                                }));
                gradleFileCount++;
                alreadyParsed.add(settingsGradleFile.toPath());
            } else if (settingsGradleKtsFile.exists()) {
                Path settingsPath = baseDir.relativize(settingsGradleKtsFile.toPath());
                sourceFiles = Stream.concat(
                        sourceFiles,
                        PlainTextParser.builder().build()
                                .parse(singleton(settingsGradleKtsFile.toPath()), baseDir, ctx)
                                .map(sourceFile -> {
                                    if (finalGs == null) {
                                        return sourceFile;
                                    }
                                    return sourceFile.withMarkers(sourceFile.getMarkers().add(finalGs));
                                }));
                gradleFileCount++;
                alreadyParsed.add(settingsGradleKtsFile.toPath());
            }
        }

        return SourceFileStream.build("", s -> {
        }).concat(sourceFiles, gradleFileCount);
    }

    protected SourceFileStream parseNonProjectResources(Project subproject, Set<Path> alreadyParsed, ExecutionContext ctx, List<Marker> projectProvenance, Stream<SourceFile> sourceFiles) {
        //Collect any additional yaml/properties/xml files that are NOT already in a source set.
        OmniParser omniParser = omniParser(alreadyParsed);
        List<Path> accepted = omniParser.acceptedPaths(baseDir, subproject.getProjectDir().toPath());
        return SourceFileStream.build("", s -> {})
                .concat(omniParser.parse(accepted, baseDir, ctx), accepted.size());
    }

    private OmniParser omniParser(Set<Path> alreadyParsed) {
        return OmniParser.builder(
                        OmniParser.defaultResourceParsers(),
                        PlainTextParser.builder()
                                .plainTextMasks(baseDir, extension.getPlainTextMasks())
                                .build(),
                        QuarkParser.builder().build()
                )
                .exclusionMatchers(pathMatchers(baseDir, mergeExclusions(project, baseDir, extension)))
                .exclusions(alreadyParsed)
                .sizeThresholdMb(extension.getSizeThresholdMb())
                .build();
    }

    private static Collection<String> mergeExclusions(Project project, Path baseDir, RewriteExtension extension) {
        return Stream.concat(
                project.getSubprojects().stream()
                        .map(subproject -> separatorsToUnix(baseDir.relativize(subproject.getProjectDir().toPath()).toString())),
                extension.getExclusions().stream()
        ).collect(toList());
    }

    private Collection<PathMatcher> pathMatchers(Path basePath, Collection<String> pathExpressions) {
        return pathExpressions.stream()
                .map(o -> basePath.getFileSystem().getPathMatcher("glob:" + o))
                .collect(toList());
    }

    private SourceFileStream parseMultiplatformKotlinProject(Project subproject, Collection<PathMatcher> exclusions, Set<Path> alreadyParsed, ExecutionContext ctx) {
        Object kotlinExtension = subproject.getExtensions().getByName("kotlin");
        NamedDomainObjectContainer<KotlinSourceSet> sourceSets;
        try {
            Class<?> clazz = kotlinExtension.getClass().getClassLoader().loadClass("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension");
            //noinspection unchecked
            sourceSets = (NamedDomainObjectContainer<KotlinSourceSet>) clazz.getMethod("getSourceSets")
                    .invoke(kotlinExtension);

        } catch (Exception e) {
            logger.warn("Failed to resolve KotlinMultiplatformExtension from {}. No sources files from KotlinMultiplatformExtension will be parsed.",
                    subproject.getPath());
            return SourceFileStream.build(subproject.getPath(), s -> {
            });
        }

        SourceFileStream sourceFileStream = SourceFileStream.build(subproject.getPath(), s -> {
        });
        SortedSet<String> sourceSetNames;
        try {
            //noinspection unchecked
            sourceSetNames = (SortedSet<String>) sourceSets.getClass().getMethod("getNames")
                    .invoke(sourceSets);
        } catch (Exception e) {
            logger.warn("Failed to resolve SourceSetNames in KotlinMultiplatformExtension from {}. No sources files from KotlinMultiplatformExtension will be parsed.",
                    subproject.getPath());
            return sourceFileStream;
        }

        for (String sourceSetName : sourceSetNames) {
            try {
                Object sourceSet = sourceSets.getClass().getMethod("getByName", String.class)
                        .invoke(sourceSets, sourceSetName);
                SourceDirectorySet kotlinDirectorySet = (SourceDirectorySet) sourceSet.getClass().getMethod("getKotlin").invoke(sourceSet);
                List<Path> kotlinPaths = kotlinDirectorySet.getFiles().stream()
                        .filter(it -> it.isFile() && it.getName().endsWith(".kt"))
                        .map(File::toPath)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .collect(toList());

                // classpath doesn't include the transitive dependencies of the implementation configuration
                // These aren't needed for compilation, but we want them so recipes have access to comprehensive type information
                // The implementation configuration isn't resolvable, so we need a new configuration that extends from it
                String implementationName = (String) sourceSet.getClass().getMethod("getImplementationConfigurationName").invoke(sourceSet);
                Configuration implementation = subproject.getConfigurations().getByName(implementationName);
                Configuration rewriteImplementation = subproject.getConfigurations().maybeCreate("rewrite" + implementationName);
                rewriteImplementation.extendsFrom(implementation);

                Set<File> implementationClasspath;
                try {
                    implementationClasspath = rewriteImplementation.resolve();
                } catch (Exception e) {
                    logger.warn("Failed to resolve dependencies from {}:{}. Some type information may be incomplete",
                            subproject.getPath(), implementationName);
                    implementationClasspath = emptySet();
                }

                String compileName = (String) sourceSet.getClass().getMethod("getCompileOnlyConfigurationName").invoke(sourceSet);
                Configuration compileOnly = subproject.getConfigurations().getByName(compileName);
                Configuration rewriteCompileOnly = subproject.getConfigurations().maybeCreate("rewrite" + compileName);
                rewriteCompileOnly.setCanBeResolved(true);
                rewriteCompileOnly.extendsFrom(compileOnly);

                // The implementation configuration doesn't include build/source directories from project dependencies
                // So mash it and our rewriteImplementation together to get everything
                List<Path> dependencyPaths = Stream.concat(implementationClasspath.stream(), rewriteCompileOnly.getFiles().stream())
                        .map(File::toPath)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .distinct()
                        .collect(toList());

                if (!kotlinPaths.isEmpty()) {
                    JavaTypeCache javaTypeCache = new JavaTypeCache();
                    KotlinParser kp = KotlinParser.builder()
                            .classpath(dependencyPaths)
                            .styles(getStyles())
                            .typeCache(javaTypeCache)
                            .logCompilationWarningsAndErrors(extension.getLogCompilationWarningsAndErrors())
                            .build();

                    Stream<SourceFile> cus = kp.parse(kotlinPaths, baseDir, ctx);
                    alreadyParsed.addAll(kotlinPaths);
                    cus = cus.map(cu -> {
                        if (isExcluded(exclusions, cu.getSourcePath())) {
                            return null;
                        }
                        return cu;
                    }).filter(Objects::nonNull);
                    JavaSourceSet sourceSetProvenance = JavaSourceSet.build(sourceSetName, dependencyPaths, javaTypeCache, false);

                    sourceFileStream = sourceFileStream.concat(cus.map(addProvenance(sourceSetProvenance)), kotlinPaths.size());
                    logger.info("Scanned {} Kotlin sources in {}/{}", kotlinPaths.size(), subproject.getPath(), kotlinDirectorySet.getName());
                }
                return sourceFileStream;
            } catch (Exception e) {
                logger.warn("Failed to resolve sourceSet from {}:{}. Some type information may be incomplete",
                        subproject.getPath(), sourceSetName);
            }
        }

        return sourceFileStream;
    }

    private SourceFile logParseErrors(SourceFile source) {
        if (source instanceof ParseError) {
            if (firstWarningLogged.compareAndSet(false, true)) {
                logger.warn("There were problems parsing some source files, run with --info to see full stack traces");
            }
            logger.warn("There were problems parsing " + source.getSourcePath());
        }
        return source;
    }

    private boolean isExcluded(Collection<PathMatcher> exclusions, Path path) {
        for (PathMatcher excluded : exclusions) {
            if (excluded.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private List<NamedStyles> getStyles() {
        if (styles == null) {
            styles = environment().activateStyles(getActiveStyles());
            File checkstyleConfig = extension.getCheckstyleConfigFile();
            if (checkstyleConfig != null && checkstyleConfig.exists()) {
                try {
                    styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(checkstyleConfig.toPath(), extension.getCheckstyleProperties()));
                } catch (Exception e) {
                    logger.warn("Unable to parse Checkstyle configuration", e);
                }
            }
        }
        return styles;
    }

    protected ResultsContainer listResults(ExecutionContext ctx) {
        Environment env = environment();
        Recipe recipe = env.activateRecipes(getActiveRecipes());
        if (recipe.getRecipeList().isEmpty()) {
            logger.warn("No recipes were activated. Activate a recipe with rewrite.activeRecipe(\"com.fully.qualified.RecipeClassName\") in your build file, or on the command line with -DactiveRecipe=com.fully.qualified.RecipeClassName");
            return new ResultsContainer(baseDir, null);
        }
        logger.lifecycle("Validating active recipes");
        Collection<Validated<Object>> validated = recipe.validateAll(ctx, new ArrayList<>());
        List<Validated.Invalid<Object>> failedValidations = validated.stream().map(Validated::failures)
                .flatMap(Collection::stream).collect(toList());
        if (!failedValidations.isEmpty()) {
            failedValidations.forEach(failedValidation -> logger.error("Recipe validation error in {}: {}", failedValidation.getProperty(), failedValidation.getMessage(), failedValidation.getException()));
            if (extension.getFailOnInvalidActiveRecipes()) {
                throw new RuntimeException("Recipe validation errors detected as part of one or more activeRecipe(s). Please check error logs.");
            } else {
                logger.error("Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless.");
            }
        }

        org.openrewrite.java.style.Autodetect.Detector javaDetector = org.openrewrite.java.style.Autodetect.detector();
        org.openrewrite.kotlin.style.Autodetect.Detector kotlinDetector = org.openrewrite.kotlin.style.Autodetect.detector();
        org.openrewrite.xml.style.Autodetect.Detector xmlDetector = org.openrewrite.xml.style.Autodetect.detector();
        List<SourceFile> sourceFiles = parse(ctx)
                .peek(s -> {
                    if (s instanceof K.CompilationUnit) {
                        kotlinDetector.sample(s);
                    } else if (s instanceof J.CompilationUnit) {
                        javaDetector.sample(s);
                    }
                })
                .peek(xmlDetector::sample)
                .collect(toList());
        Map<Class<? extends Tree>, NamedStyles> stylesByType = new HashMap<>();
        stylesByType.put(J.CompilationUnit.class, javaDetector.build());
        stylesByType.put(K.CompilationUnit.class, kotlinDetector.build());
        stylesByType.put(Xml.Document.class, xmlDetector.build());
        sourceFiles = ListUtils.map(sourceFiles, applyAutodetectedStyle(stylesByType));

        logger.lifecycle("All sources parsed, running active recipes: {}", String.join(", ", getActiveRecipes()));
        RecipeRun recipeRun = recipe.run(new InMemoryLargeSourceSet(sourceFiles), ctx);
        return new ResultsContainer(baseDir, recipeRun);
    }

    public void shutdownRewrite() {
        GradleProjectBuilder.clearCaches();
    }

    private UnaryOperator<SourceFile> applyAutodetectedStyle(Map<Class<? extends Tree>, NamedStyles> stylesByType) {
        return before -> {
            for (Map.Entry<Class<? extends Tree>, NamedStyles> styleTypeEntry : stylesByType.entrySet()) {
                if (styleTypeEntry.getKey().isAssignableFrom(before.getClass())) {
                    before = before.withMarkers(before.getMarkers().add(styleTypeEntry.getValue()));
                }
            }
            return before;
        };
    }

    private <T extends SourceFile> UnaryOperator<T> addProvenance(List<Marker> projectProvenance) {
        return s -> {
            Markers m = s.getMarkers();
            for (Marker marker : projectProvenance) {
                m = m.addIfAbsent(marker);
            }
            return s.withMarkers(m);
        };
    }

    private <T extends SourceFile> UnaryOperator<T> addProvenance(Marker sourceSet) {
        return s -> {
            Markers m = s.getMarkers();
            m = m.addIfAbsent(sourceSet);
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
        logger.warn("{}", recipeString);
        for (RecipeDescriptor rChild : rd.getRecipeList()) {
            logRecipe(rChild, prefix + "    ");
        }
    }
}

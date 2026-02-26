/*
 * Copyright 2025 the original author or authors.
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
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.GroovyPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.compile.CompileOptions;
import org.gradle.api.tasks.compile.GroovyCompile;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.invocation.DefaultGradle;
import org.gradle.util.GradleVersion;
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.binary.Binary;
import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.gradle.GradleParser;
import org.openrewrite.gradle.GradleProjectParser;
import org.openrewrite.gradle.RewriteExtension;
import org.openrewrite.gradle.SanitizedMarkerPrinter;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.gradle.marker.GradleProjectBuilder;
import org.openrewrite.gradle.marker.GradleSettings;
import org.openrewrite.gradle.marker.GradleSettingsBuilder;
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.java.style.CheckstyleConfigLoader;
import org.openrewrite.java.tree.J;
import org.openrewrite.jgit.api.Git;
import org.openrewrite.jgit.lib.FileMode;
import org.openrewrite.jgit.lib.ObjectId;
import org.openrewrite.jgit.lib.Repository;
import org.openrewrite.jgit.revwalk.RevCommit;
import org.openrewrite.jgit.revwalk.RevWalk;
import org.openrewrite.jgit.treewalk.FileTreeIterator;
import org.openrewrite.jgit.treewalk.TreeWalk;
import org.openrewrite.jgit.treewalk.WorkingTreeIterator;
import org.openrewrite.jgit.treewalk.filter.PathFilter;
import org.openrewrite.jgit.treewalk.filter.PathFilterGroup;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.marker.*;
import org.openrewrite.marker.ci.BuildEnvironment;
import org.openrewrite.polyglot.*;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.quark.Quark;
import org.openrewrite.quark.QuarkParser;
import org.openrewrite.remote.Remote;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.text.PlainTextParser;
import org.openrewrite.tree.ParsingEventListener;
import org.openrewrite.tree.ParsingExecutionContextView;
import org.openrewrite.xml.tree.Xml;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.util.Collections.*;
import static java.util.stream.Collectors.*;
import static org.openrewrite.PathUtils.separatorsToUnix;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.tree.ParsingExecutionContextView.view;

public class DefaultProjectParser implements GradleProjectParser {
    private static final String LOG_INDENT_INCREMENT = "    ";

    private static final Logger logger = Logging.getLogger(DefaultProjectParser.class);
    private final AtomicBoolean firstWarningLogged = new AtomicBoolean(false);
    protected final Path baseDir;
    protected final RewriteExtension extension;
    protected final Project project;
    private final List<Marker> sharedProvenance;

    @Nullable
    protected final Repository repository;

    @Nullable
    private List<NamedStyles> styles;

    @Nullable
    private Environment environment;

    @Nullable
    private AndroidProjectParser androidProjectParser;

    public DefaultProjectParser(Project project, RewriteExtension extension) {
        this.baseDir = repositoryRoot(project);
        this.repository = getRepository(baseDir);
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

    static @Nullable Repository getRepository(Path rootDir) {
        try {
            // Repository is closed in `shutdownRewrite()`
            //noinspection resource
            Git git = Git.open(rootDir.toFile());
            return git.getRepository();
        } catch (IOException e) {
            // no git
            return null;
        }
    }

    private static final Map<Path, GitProvenance> REPO_ROOT_TO_PROVENANCE = new HashMap<>();

    private @Nullable GitProvenance gitProvenance(Path baseDir, @Nullable BuildEnvironment buildEnvironment) {
        try {
            // Computing git provenance can be expensive for repositories with many commits, ensure we do it only once per build
            // To avoid old state being used on accident in new builds on the same daemon, cache is cleared in the shutdown hook
            return REPO_ROOT_TO_PROVENANCE.computeIfAbsent(baseDir, dir -> GitProvenance.fromProjectDirectory(dir, buildEnvironment));
        } catch (Exception e) {
            // Logging at a low level as this is unlikely to happen except in non-git projects, where it is expected
            logger.debug("Unable to determine git provenance", e);
        }
        return null;
    }

    // By accident, we were inconsistent with the names of these properties between this and the maven plugin
    // Check all variants of the name, preferring more-fully-qualified names
    private @Nullable String getPropertyWithVariantNames(String property) {
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

    private static boolean isAndroidProject(Project project) {
        return project.hasProperty("android");
    }

    private AndroidProjectParser getAndroidProjectParser() {
        if (androidProjectParser == null) {
            androidProjectParser = new AndroidProjectParser(baseDir, repository, extension, getStyles());
        }
        return androidProjectParser;
    }

    @Override
    public List<String> getActiveRecipes() {
        String activeRecipe = getPropertyWithVariantNames("activeRecipe");
        if (activeRecipe == null) {
            return new ArrayList<>(extension.getActiveRecipes());
        }
        return Arrays.asList(activeRecipe.split(","));
    }

    @Override
    public List<String> getActiveStyles() {
        String activeStyle = getPropertyWithVariantNames("activeStyle");
        if (activeStyle == null) {
            return new ArrayList<>(extension.getActiveStyles());
        }
        return Arrays.asList(activeStyle.split(","));
    }

    @Override
    public List<String> getAvailableStyles() {
        return environment().listStyles().stream().map(NamedStyles::getName).collect(toList());
    }

    @Override
    public void discoverRecipes(ServiceRegistry serviceRegistry) {
        Collection<RecipeDescriptor> availableRecipeDescriptors = this.listRecipeDescriptors();

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

    public Collection<RecipeDescriptor> listRecipeDescriptors() {
        return environment().listRecipeDescriptors();
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

    @Override
    public Collection<Path> listSources() {
        // Use a sorted collection so that gradle input detection isn't thrown off by ordering
        Set<Path> result = new TreeSet<>(omniParser(emptySet(), project).acceptedPaths(
                baseDir,
                project.getProjectDir().toPath()));
        if (isAndroidProject(project)) {
            getAndroidProjectParser().findSourceDirectories(project)
                    .stream()
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .forEach(result::add);
        } else {
            for (SourceSet sourceSet : findGradleSourceSets(project)) {
                sourceSet.getAllSource()
                        .getFiles()
                        .stream()
                        .map(File::toPath)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .forEach(result::add);
            }
        }
        return result;
    }

    @Override
    public void dryRun(Path reportPath, boolean dumpGcActivity, Consumer<Throwable> onError) {
        ParsingExecutionContextView ctx = view(new InMemoryExecutionContext(onError));
        if (dumpGcActivity) {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            try (JvmHeapPressureMetrics heapMetrics = new JvmHeapPressureMetrics()) {
                heapMetrics.bindTo(meterRegistry);
                new JvmMemoryMetrics().bindTo(meterRegistry);

                File rewriteBuildDir = project.getLayout().getBuildDirectory().dir("rewrite").get().getAsFile();
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
                Duration estimateTimeSaved = Duration.ZERO;
                for (Result result : results.generated) {
                    assert result.getAfter() != null;
                    logger.warn("These recipes would generate new file {}:", result.getAfter().getSourcePath());
                    logRecipesThatMadeChanges(result);
                    estimateTimeSaved = estimateTimeSavedSum(result, estimateTimeSaved);
                }
                for (Result result : results.deleted) {
                    assert result.getBefore() != null;
                    logger.warn("These recipes would delete file {}:", result.getBefore().getSourcePath());
                    logRecipesThatMadeChanges(result);
                    estimateTimeSaved = estimateTimeSavedSum(result, estimateTimeSaved);
                }
                for (Result result : results.moved) {
                    assert result.getBefore() != null;
                    assert result.getAfter() != null;
                    logger.warn("These recipes would move file from {} to {}:", result.getBefore().getSourcePath(), result.getAfter().getSourcePath());
                    logRecipesThatMadeChanges(result);
                    estimateTimeSaved = estimateTimeSavedSum(result, estimateTimeSaved);
                }
                for (Result result : results.refactoredInPlace) {
                    assert result.getBefore() != null;
                    logger.warn("These recipes would make changes to {}:", result.getBefore().getSourcePath());
                    logRecipesThatMadeChanges(result);
                    estimateTimeSaved = estimateTimeSavedSum(result, estimateTimeSaved);
                }

                //noinspection ResultOfMethodCallIgnored
                reportPath.getParent().toFile().mkdirs();
                try (BufferedWriter writer = Files.newBufferedWriter(reportPath)) {
                    Stream.concat(
                                    Stream.concat(results.generated.stream(), results.deleted.stream()),
                                    Stream.concat(results.moved.stream(), results.refactoredInPlace.stream()))
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
                logger.warn("Estimate time saved: {}", formatDuration(estimateTimeSaved));
                logger.warn("Run 'gradle rewriteRun' to apply the recipes.");

                if (project.getExtensions().getByType(RewriteExtension.class).getFailOnDryRunResults()) {
                    throw new RuntimeException("Applying recipes would make changes. See logs for more details.");
                }
            } else {
                logger.lifecycle("Applying recipes would make no changes. No report generated.");
            }
        } finally {
            shutdownRewrite();
        }
    }

    private static String formatDuration(Duration duration) {
        return duration.toString()
                .substring(2)
                .replaceAll("(\\d[HMS])(?!$)", "$1 ")
                .toLowerCase()
                .trim();
    }

    @Override
    public void run(Consumer<Throwable> onError) {
        ExecutionContext ctx = new InMemoryExecutionContext(onError);
        run(listResults(ctx), ctx);
    }

    public void run(ResultsContainer results, ExecutionContext ctx) {
        try {
            if (results.isNotEmpty()) {
                Duration estimateTimeSaved = Duration.ZERO;
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
                    estimateTimeSaved = estimateTimeSavedSum(result, estimateTimeSaved);
                }
                for (Result result : results.deleted) {
                    assert result.getBefore() != null;
                    logger.lifecycle("Deleted file " +
                                     result.getBefore().getSourcePath() +
                                     " by:");
                    logRecipesThatMadeChanges(result);
                    estimateTimeSaved = estimateTimeSavedSum(result, estimateTimeSaved);
                }
                for (Result result : results.moved) {
                    assert result.getAfter() != null;
                    assert result.getBefore() != null;
                    logger.lifecycle("File has been moved from " +
                                     result.getBefore().getSourcePath() + " to " +
                                     result.getAfter().getSourcePath() + " by:");
                    logRecipesThatMadeChanges(result);
                    estimateTimeSaved = estimateTimeSavedSum(result, estimateTimeSaved);
                }
                for (Result result : results.refactoredInPlace) {
                    assert result.getBefore() != null;
                    logger.lifecycle("Changes have been made to " +
                                     result.getBefore().getSourcePath() +
                                     " by:");
                    logRecipesThatMadeChanges(result);
                    estimateTimeSaved = estimateTimeSavedSum(result, estimateTimeSaved);
                }

                logger.lifecycle("Please review and commit the results.");

                logger.lifecycle("Estimate time saved: {}", formatDuration(estimateTimeSaved));

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
                        if (afterParentDir.exists() &&
                            afterParentDir.getAbsolutePath().equalsIgnoreCase((originalParentDir.getAbsolutePath())) &&
                            !afterParentDir.getAbsolutePath().equals(originalParentDir.getAbsolutePath())) {
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

    private static Duration estimateTimeSavedSum(Result result, Duration timeSaving) {
        if (null != result.getTimeSavings()) {
            return timeSaving.plus(result.getTimeSavings());
        }
        return timeSaving;
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
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

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
        return Stream.concat(builder, parse(project, alreadyParsed, ctx)).map(this::logParseErrors);
    }

    public Stream<SourceFile> parse(Project subproject, Set<Path> alreadyParsed, ExecutionContext ctx) {
        String cliPort = System.getenv("MODERNE_CLI_PORT");
        try (ProgressBar progressBar = StringUtils.isBlank(cliPort) ? new NoopProgressBar() :
                new RemoteProgressBarSender(Integer.parseInt(cliPort))) {
            SourceFileStream sourceFileStream = SourceFileStream.build(
                    subproject.getPath(),
                    projectName -> progressBar.intermediateResult(":" + projectName));

            Collection<PathMatcher> exclusions = extension.getExclusions().stream()
                    .map(pattern -> subproject.getProjectDir().toPath().getFileSystem().getPathMatcher("glob:" + pattern))
                    .collect(toList());
            if (isExcluded(repository, exclusions, baseDir.relativize(subproject.getProjectDir().toPath()))) {
                logger.lifecycle("Skipping project {} because it is excluded", subproject.getPath());
                return Stream.empty();
            }

            logger.lifecycle("Scanning sources in project {}", subproject.getPath());
            List<NamedStyles> styles = getStyles();
            logger.lifecycle("Using active styles {}", styles.stream().map(NamedStyles::getName).collect(toList()));

            if (subproject.getPlugins().hasPlugin("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension") ||
                subproject.getExtensions()
                        .findByName("kotlin") != null && subproject.getExtensions()
                        .getByName("kotlin")
                        .getClass()
                        .getCanonicalName()
                        .startsWith("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension")) {
                sourceFileStream = sourceFileStream.concat(parseMultiplatformKotlinProject(
                        subproject,
                        exclusions,
                        alreadyParsed,
                        ctx));
            }

            Path buildDirPath = baseDir.relativize(subproject.getLayout()
                    .getBuildDirectory()
                    .get()
                    .getAsFile()
                    .toPath());

            SourceFileStream projectSourceFileStream;
            if (isAndroidProject(subproject)) {
                projectSourceFileStream = parseAndroidProjectSourceSets(
                        subproject,
                        progressBar,
                        buildDirPath,
                        alreadyParsed,
                        exclusions,
                        ctx);
            } else {
                projectSourceFileStream = parseGradleProjectSourceSets(
                        subproject,
                        progressBar,
                        buildDirPath,
                        alreadyParsed,
                        exclusions,
                        ctx);
            }
            sourceFileStream = sourceFileStream.concat(projectSourceFileStream, projectSourceFileStream.size());
            List<Marker> projectProvenance;
            if (projectSourceFileStream.size() == 0) {
                projectProvenance = sharedProvenance;
            } else {
                projectProvenance = new ArrayList<>(sharedProvenance);
                projectProvenance.add(new JavaProject(
                        randomId(),
                        subproject.getName(),
                        new JavaProject.Publication(
                                subproject.getGroup().toString(),
                                subproject.getName(),
                                subproject.getVersion().toString())));
            }

            SourceFileStream gradleFiles = parseGradleFiles(subproject, exclusions, alreadyParsed, ctx);
            sourceFileStream = sourceFileStream.concat(gradleFiles, gradleFiles.size());

            SourceFileStream gradleWrapperFiles = parseGradleWrapperFiles(exclusions, alreadyParsed, ctx);
            sourceFileStream = sourceFileStream.concat(gradleWrapperFiles, gradleWrapperFiles.size());

            SourceFileStream nonProjectResources = parseNonProjectResources(subproject, alreadyParsed, ctx);
            sourceFileStream = sourceFileStream.concat(nonProjectResources, nonProjectResources.size());

            return sourceFileStream.map(addProvenance(projectProvenance))
                    .map(addGitTreeEntryInformation());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SourceFileStream parseGradleProjectSourceSets(Project subproject,
                                                          ProgressBar progressBar,
                                                          Path buildDir,
                                                          Set<Path> alreadyParsed,
                                                          Collection<PathMatcher> exclusions,
                                                          ExecutionContext ctx) {
        SourceFileStream sourceFileStream = SourceFileStream.build(
                subproject.getPath(),
                projectName -> progressBar.intermediateResult(":" + projectName));

        for (SourceSet sourceSet : findGradleSourceSets(subproject)) {
            Stream<SourceFile> sourceSetSourceFiles = Stream.of();
            int sourceSetSize = 0;

            JavaTypeCache javaTypeCache = new JavaTypeCache();
            JavaCompile javaCompileTask = (JavaCompile) subproject.getTasks()
                    .getByName(sourceSet.getCompileJavaTaskName());
            JavaVersion javaVersion = getJavaVersion(javaCompileTask);

            List<Path> unparsedSources = sourceSet.getAllSource()
                    .getSourceDirectories()
                    .filter(File::exists)
                    .filter(dir -> !alreadyParsed.contains(dir.toPath()))
                    .getFiles()
                    .stream()
                    .map(File::toPath)
                    .flatMap(dirPath -> {
                        try {
                            return Files.walk(dirPath);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .filter(Files::isRegularFile)
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .distinct()
                    .collect(toList());

            List<Path> javaPaths = unparsedSources.stream()
                    .filter(path -> !alreadyParsed.contains(path))
                    .filter(path -> path.toString().endsWith(".java"))
                    .collect(toList());

            // The compilation classpath doesn't include the transitive dependencies
            // The runtime classpath doesn't include compile only dependencies, e.g.: lombok, servlet-api
            // So we use both together to get comprehensive type information
            Set<Path> dependencyPaths = new HashSet<>();
            try {
                Stream.concat(
                                sourceSet.getRuntimeClasspath().getFiles().stream(),
                                sourceSet.getCompileClasspath().getFiles().stream())
                        .map(File::toPath)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .forEach(dependencyPaths::add);
            } catch (Exception e) {
                logger.warn(
                        "Unable to resolve classpath for sourceSet {}:{}",
                        subproject.getPath(),
                        sourceSet.getName(),
                        e);
            }

            if (!javaPaths.isEmpty()) {
                alreadyParsed.addAll(javaPaths);
                Stream<SourceFile> parsedJavaFiles = parseJavaFiles(
                        javaPaths,
                        ctx,
                        buildDir,
                        exclusions,
                        getSourceFileEncoding(javaCompileTask.getOptions()),
                        javaVersion,
                        dependencyPaths,
                        javaTypeCache);
                sourceSetSourceFiles = Stream.concat(sourceSetSourceFiles, parsedJavaFiles);
                sourceSetSize += javaPaths.size();
                logger.info(
                        "Scanned {} Java sources in {}/{}",
                        javaPaths.size(),
                        subproject.getPath(),
                        sourceSet.getName());
            }

            if (subproject.getPlugins().hasPlugin("org.jetbrains.kotlin.jvm")) {
                String excludedProtosPath = subproject.getProjectDir().getPath() + "/protos/build/generated";
                List<Path> kotlinPaths = unparsedSources.stream()
                        .filter(it -> it.toString().endsWith(".kt"))
                        .filter(it -> !it.toString().startsWith(excludedProtosPath))
                        .collect(toList());

                if (!kotlinPaths.isEmpty()) {
                    alreadyParsed.addAll(kotlinPaths);
                    Stream<SourceFile> parsedKotlinFiles = parseKotlinFiles(
                            kotlinPaths,
                            ctx,
                            buildDir,
                            exclusions,
                            javaVersion,
                            dependencyPaths,
                            javaTypeCache);
                    sourceSetSourceFiles = Stream.concat(sourceSetSourceFiles, parsedKotlinFiles);
                    sourceSetSize += kotlinPaths.size();
                    logger.info(
                            "Scanned {} Kotlin sources in {}/{}",
                            kotlinPaths.size(),
                            subproject.getPath(),
                            sourceSet.getName());
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
                                    sourceSet.getOutput().getClassesDirs().getFiles().stream().map(File::toPath))
                            .collect(toList());

                    alreadyParsed.addAll(groovyPaths);

                    GroovyCompile groovyCompileTask = (GroovyCompile) subproject.getTasks()
                            .getByName(sourceSet.getCompileTaskName("groovy"));
                    Stream<SourceFile> cus = Stream.of((Supplier<GroovyParser>) () -> GroovyParser.builder()
                            .classpath(dependenciesWithBuildDirs)
                            .typeCache(javaTypeCache)
                            .logCompilationWarningsAndErrors(false)
                            .build())
                            .map(Supplier::get)
                            .flatMap(gp -> {
                                view(ctx).setCharset(getSourceFileEncoding(groovyCompileTask.getOptions()));
                                return gp.parse(groovyPaths, baseDir, ctx).onClose(() -> view(ctx).setCharset(null));
                            }).map(cu -> {
                        if (isExcluded(repository, exclusions, cu.getSourcePath()) || cu.getSourcePath().startsWith(buildDir)) {
                            return null;
                        }
                        return cu;
                    }).filter(Objects::nonNull).map(it -> it.withMarkers(it.getMarkers().add(javaVersion)));
                    sourceSetSourceFiles = Stream.concat(sourceSetSourceFiles, cus);
                    sourceSetSize += groovyPaths.size();
                    logger.info(
                            "Scanned {} Groovy sources in {}/{}",
                            groovyPaths.size(),
                            subproject.getPath(),
                            sourceSet.getName());
                }
            }

            for (File resourcesDir : sourceSet.getResources().getSourceDirectories()) {
                if (resourcesDir.exists() && !alreadyParsed.contains(resourcesDir.toPath())) {
                    OmniParser omniParser = omniParser(alreadyParsed, subproject);
                    List<Path> accepted = omniParser.acceptedPaths(baseDir, resourcesDir.toPath());
                    sourceSetSourceFiles = Stream.concat(
                            sourceSetSourceFiles,
                            omniParser.parse(accepted, baseDir, ctx)
                                    .map(it -> it.withMarkers(it.getMarkers().add(javaVersion))));
                    alreadyParsed.addAll(accepted);
                    sourceSetSize += accepted.size();
                }
            }

            JavaSourceSet sourceSetProvenance = JavaSourceSet.build(sourceSet.getName(), dependencyPaths);
            sourceFileStream = sourceFileStream.concat(
                    sourceSetSourceFiles.map(addProvenance(sourceSetProvenance)),
                    sourceSetSize);
            // Some source sets get misconfigured to have the same directories as other source sets
            // Prevent files which appear in multiple source sets from being parsed more than once
            for (File file : sourceSet.getAllSource().getSourceDirectories().getFiles()) {
                alreadyParsed.add(file.toPath());
            }
        }
        return sourceFileStream;
    }

    private SourceFileStream parseAndroidProjectSourceSets(
            Project subproject,
            ProgressBar progressBar,
            Path buildDir,
            Set<Path> alreadyParsed,
            Collection<PathMatcher> exclusions,
            ExecutionContext ctx) {
        return getAndroidProjectParser().parseProjectSourceSets(
                subproject,
                progressBar,
                buildDir,
                alreadyParsed,
                exclusions,
                ctx,
                omniParser(alreadyParsed, subproject));
    }

    private Stream<SourceFile> parseJavaFiles(
            List<Path> javaPaths,
            ExecutionContext ctx,
            Path buildDir,
            Collection<PathMatcher> exclusions,
            Charset javaSourceCharset,
            JavaVersion javaVersion,
            Set<Path> dependencyPaths,
            JavaTypeCache javaTypeCache) {
        return Stream.of((Supplier<JavaParser>) () -> JavaParser.fromJavaVersion()
                        .classpath(dependencyPaths)
                        .typeCache(javaTypeCache)
                        .logCompilationWarningsAndErrors(extension.getLogCompilationWarningsAndErrors())
                        .build())
                .map(Supplier::get)
                .flatMap(jp -> {
                    view(ctx).setCharset(javaSourceCharset);
                    return jp.parse(javaPaths, baseDir, ctx).onClose(() -> view(ctx).setCharset(null));
                })
                .map(cu -> {
                    if (isExcluded(repository, exclusions, cu.getSourcePath()) || cu.getSourcePath().startsWith(buildDir)) {
                        return null;
                    }
                    return cu;
                }).filter(Objects::nonNull).map(it -> it.withMarkers(it.getMarkers().add(javaVersion)));
    }

    private Stream<SourceFile> parseKotlinFiles(List<Path> kotlinPaths,
                                                ExecutionContext ctx,
                                                Path buildDir,
                                                Collection<PathMatcher> exclusions,
                                                JavaVersion javaVersion,
                                                Set<Path> dependencyPaths,
                                                JavaTypeCache javaTypeCache) {
        return Stream.of((Supplier<KotlinParser>) () -> KotlinParser.builder()
                        .classpath(dependencyPaths)
                        .typeCache(javaTypeCache)
                        .logCompilationWarningsAndErrors(extension.getLogCompilationWarningsAndErrors())
                        .build())
                .map(Supplier::get)
                .flatMap(kp -> {
                    view(ctx).setCharset(StandardCharsets.UTF_8); // Kotlin requires UTF-8
                    return kp.parse(kotlinPaths, baseDir, ctx).onClose(() -> view(ctx).setCharset(null));
                })
                .map(cu -> {
                    if (isExcluded(repository, exclusions, cu.getSourcePath()) || cu.getSourcePath().startsWith(buildDir)) {
                        return null;
                    }
                    return cu;
                }).filter(Objects::nonNull).map(it -> it.withMarkers(it.getMarkers().add(javaVersion)));
    }

    private GradleParser gradleParser() {
        List<Path> settingsClasspath;
        if (GradleVersion.current().compareTo(GradleVersion.version("4.4")) >= 0) {
            try {
                Settings settings = ((DefaultGradle) project.getGradle()).getSettings();
                settingsClasspath = settings.getBuildscript()
                        .getConfigurations()
                        .getByName("classpath")
                        .resolve()
                        .stream()
                        .map(File::toPath)
                        .collect(toList());
            } catch (IllegalStateException e) {
                settingsClasspath = emptyList();
            }
        } else {
            settingsClasspath = emptyList();
        }
        List<Path> buildscriptClasspath = project.getBuildscript()
                .getConfigurations()
                .getByName("classpath")
                .resolve()
                .stream()
                .map(File::toPath)
                .collect(toList());

        JavaTypeCache typeCache = new JavaTypeCache();
        return GradleParser.builder()
                .groovyParser(GroovyParser.builder()
                        .typeCache(typeCache)
                        .logCompilationWarningsAndErrors(false))
                .kotlinParser(KotlinParser.builder()
                        .typeCache(typeCache)
                        .logCompilationWarningsAndErrors(false))
                .buildscriptClasspath(buildscriptClasspath)
                .settingsClasspath(settingsClasspath)
                .build();
    }

    private SourceFileStream parseGradleFiles(
            Project subproject,
            Collection<PathMatcher> exclusions,
            Set<Path> alreadyParsed,
            ExecutionContext ctx) {
        // Eagerly evaluate parsed gradle files so that parse errors are caught per-file,
        // rather than propagating up during lazy stream evaluation (see #424)
        List<SourceFile> sourceFiles = new ArrayList<>();
        int gradleFileCount = 0;

        // build.gradle
        GradleParser gradleParser = null;
        GradleProject gradleProject = GradleProjectBuilder.gradleProject(subproject);
        File buildGradleFile = subproject.getBuildscript().getSourceFile();
        if (buildGradleFile != null) {
            Path buildScriptPath = baseDir.relativize(buildGradleFile.toPath());
            if (!isExcluded(repository, exclusions, buildScriptPath) && buildGradleFile.exists()) {
                gradleParser = gradleParser();
                try {
                    sourceFiles.addAll(gradleParser.parse(singleton(buildGradleFile.toPath()), baseDir, ctx)
                            .map(sourceFile -> (SourceFile) sourceFile.withMarkers(sourceFile.getMarkers().add(gradleProject)))
                            .collect(toList()));
                } catch (Exception e) {
                    logger.warn("Failed to parse {}", buildGradleFile, e);
                }
                gradleFileCount++;
                alreadyParsed.add(buildGradleFile.toPath());
            }
        }

        // settings.gradle
        if (subproject == project.getRootProject()) {
            File settingsGradleFile = determineGradleSettingsFile(subproject);
            if (settingsGradleFile != null) {
                Path settingsPath = baseDir.relativize(settingsGradleFile.toPath());
                if (!isExcluded(repository, exclusions, settingsPath)) {
                    GradleSettings gs = null;
                    if (GradleVersion.current().compareTo(GradleVersion.version("4.4")) >= 0) {
                        gs = GradleSettingsBuilder.gradleSettings(((DefaultGradle) project.getGradle()).getSettings());
                    }
                    GradleSettings finalGs = gs;
                    if (gradleParser == null) {
                        gradleParser = gradleParser();
                    }
                    try {
                        sourceFiles.addAll(gradleParser
                                .parse(singleton(settingsGradleFile.toPath()), baseDir, ctx)
                                .map(sourceFile -> {
                                    if (finalGs == null) {
                                        return sourceFile;
                                    }
                                    return (SourceFile) sourceFile.withMarkers(sourceFile.getMarkers().add(finalGs));
                                })
                                .collect(toList()));
                    } catch (Exception e) {
                        logger.warn("Failed to parse {}", settingsGradleFile, e);
                    }
                    gradleFileCount++;
                }
                alreadyParsed.add(settingsGradleFile.toPath());
            }
        }

        // gradle.properties
        File gradlePropertiesFile = subproject.file("gradle.properties");
        if (gradlePropertiesFile.exists()) {
            Path gradlePropertiesPath = baseDir.relativize(gradlePropertiesFile.toPath());
            if (!isExcluded(repository, exclusions, gradlePropertiesPath)) {
                final GradleProject finalGradleProject = gradleProject;
                try {
                    sourceFiles.addAll(new PropertiesParser()
                            .parse(singleton(gradlePropertiesFile.toPath()), baseDir, ctx)
                            .map(sourceFile -> (SourceFile) sourceFile.withMarkers(sourceFile.getMarkers().add(finalGradleProject)))
                            .collect(toList()));
                } catch (Exception e) {
                    logger.warn("Failed to parse {}", gradlePropertiesFile, e);
                }
                gradleFileCount++;
            }
            alreadyParsed.add(gradlePropertiesFile.toPath());
        }

        // Freestanding scripts
        try {
            List<Path> freeStandingScripts = new ArrayList<>();
            Files.walkFileTree(subproject.getProjectDir().toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path dirFromRoot = baseDir.relativize(dir);
                    String name = dirFromRoot.toString();
                    if (subproject.getLayout().getBuildDirectory().getAsFile().get().toPath().equals(dir) ||
                        name.startsWith(".") // Skip .gradle, .idea, .moderne, etc.
                        || "out".equals(name) // IntelliJ standard output directory
                        || subproject.getSubprojects().stream()
                                .anyMatch(sp -> dir.equals(sp.getProjectDir().toPath())) ||
                        subproject.getGradle().getIncludedBuilds().stream()
                                .anyMatch(ib -> dir.equals(ib.getProjectDir().toPath())) ||
                        isExcluded(repository, exclusions, baseDir.relativize(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if ((file.toString().endsWith(".gradle") || file.toString().endsWith(".gradle.kts")) && !alreadyParsed.contains(file) && !isExcluded(repository, exclusions, baseDir.relativize(file))) {
                        freeStandingScripts.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            if (!freeStandingScripts.isEmpty()) {
                if (gradleParser == null) {
                    gradleParser = gradleParser();
                }
                try {
                    sourceFiles.addAll(gradleParser.parse(freeStandingScripts, baseDir, ctx)
                            .map(sourceFile -> (SourceFile) sourceFile.withMarkers(sourceFile.getMarkers().add(gradleProject)))
                            .collect(toList()));
                } catch (Exception e) {
                    logger.warn("Failed to parse freestanding Gradle scripts in {}", subproject.getPath(), e);
                }
                alreadyParsed.addAll(freeStandingScripts);
                gradleFileCount += freeStandingScripts.size();
            }
        } catch (IOException e) {
            logger.warn("Unable to walk file tree for project {}", subproject.getPath(), e);
        }

        // if there is a gradle.lockfile or buildscript-gradle.lockfile parse it as plain text
        // Can be renamed according to https://docs.gradle.org/current/userguide/dependency_locking.html#sec:configuring-the-per-project-lock-file-name-and-location
        File lockfile = subproject.file("gradle.lockfile");
        if (lockfile.exists()) {
            try {
                sourceFiles.addAll(PlainTextParser.builder().build()
                        .parse(singletonList(lockfile.toPath()), baseDir, ctx)
                        .map(sourceFile -> (SourceFile) sourceFile.withMarkers(sourceFile.getMarkers().add(gradleProject)))
                        .collect(toList()));
            } catch (Exception e) {
                logger.warn("Failed to parse {}", lockfile, e);
            }
        }
        File buildscriptLockfile = subproject.file("buildscript-gradle.lockfile");
        if (buildscriptLockfile.exists()) {
            try {
                sourceFiles.addAll(PlainTextParser.builder().build()
                        .parse(singletonList(buildscriptLockfile.toPath()), baseDir, ctx)
                        .map(sourceFile -> (SourceFile) sourceFile.withMarkers(sourceFile.getMarkers().add(gradleProject)))
                        .collect(toList()));
            } catch (Exception e) {
                logger.warn("Failed to parse {}", buildscriptLockfile, e);
            }
        }

        return SourceFileStream.build("", s -> {
        }).concat(sourceFiles.stream(), gradleFileCount);
    }

    private @Nullable File determineGradleSettingsFile(Project rootProject) {
        File settingsFile = rootProject.file("settings.gradle.kts");
        if (settingsFile.exists()) {
            return settingsFile;
        }

        settingsFile = rootProject.file("settings.gradle");
        if (settingsFile.exists()) {
            return settingsFile;
        }
        return null;
    }

    /**
     * Parse Gradle wrapper files separately from other resource files, as Moderne CLI skips `parseNonProjectResources`.
     */
    private SourceFileStream parseGradleWrapperFiles(Collection<PathMatcher> exclusions, Set<Path> alreadyParsed, ExecutionContext ctx) {
        Stream<SourceFile> sourceFiles = Stream.empty();
        int fileCount = 0;
        if (project == project.getRootProject()) {
            OmniParser omniParser = omniParser(alreadyParsed, project);
            List<Path> gradleWrapperFiles = Stream.of(
                            "gradlew",
                            "gradlew.bat",
                            "gradle/wrapper/gradle-wrapper.jar",
                            "gradle/wrapper/gradle-wrapper.properties")
                    .map(project::file)
                    .filter(File::exists)
                    .map(File::toPath)
                    .filter(it -> !isExcluded(repository, exclusions, it))
                    .filter(omniParser::accept)
                    .collect(toList());
            sourceFiles = omniParser.parse(gradleWrapperFiles, baseDir, ctx);
            fileCount = gradleWrapperFiles.size();
        }
        return SourceFileStream.build("wrapper", s -> {
        }).concat(sourceFiles, fileCount);
    }

    protected SourceFileStream parseNonProjectResources(Project subproject, Set<Path> alreadyParsed, ExecutionContext ctx) {
        //Collect any additional yaml/properties/xml files that are NOT already in a source set.
        OmniParser omniParser = omniParser(alreadyParsed, subproject);
        List<Path> accepted = omniParser.acceptedPaths(baseDir, subproject.getProjectDir().toPath());
        return SourceFileStream.build("", s -> {
        }).concat(omniParser.parse(accepted, baseDir, ctx), accepted.size());
    }

    private OmniParser omniParser(Set<Path> alreadyParsed, Project project) {
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
                extension.getExclusions().stream()).collect(toList());
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

        Path buildDirPath = baseDir.relativize(subproject.getLayout().getBuildDirectory().get().getAsFile().toPath());
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
                String implementationName = (String) sourceSet.getClass()
                        .getMethod("getImplementationConfigurationName")
                        .invoke(sourceSet);
                Configuration implementation = subproject.getConfigurations().getByName(implementationName);
                Configuration rewriteImplementation = subproject.getConfigurations().maybeCreate("rewrite" + implementationName);
                if (!rewriteImplementation.getExtendsFrom().contains(implementation)) {
                    rewriteImplementation.extendsFrom(implementation);
                }

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
                            .typeCache(javaTypeCache)
                            .logCompilationWarningsAndErrors(extension.getLogCompilationWarningsAndErrors())
                            .build();

                    Stream<SourceFile> cus = kp.parse(kotlinPaths, baseDir, ctx);
                    alreadyParsed.addAll(kotlinPaths);
                    cus = cus.map(cu -> {
                        if (isExcluded(repository, exclusions, cu.getSourcePath()) ||
                            cu.getSourcePath().startsWith(buildDirPath)) {
                            return null;
                        }
                        return cu;
                    }).filter(Objects::nonNull);
                    JavaSourceSet sourceSetProvenance = JavaSourceSet.build(sourceSetName, dependencyPaths);

                    sourceFileStream = sourceFileStream.concat(cus.map(addProvenance(sourceSetProvenance)), kotlinPaths.size());
                    logger.info("Scanned {} Kotlin sources in {}/{}", kotlinPaths.size(), subproject.getPath(), kotlinDirectorySet.getName());
                }
            } catch (Exception e) {
                logger.warn("Failed to resolve sourceSet from {}:{}. Some type information may be incomplete",
                        subproject.getPath(), sourceSetName);
            }
        }

        return sourceFileStream;
    }

    private SourceFile logParseErrors(SourceFile source) {
        source.getMarkers().findFirst(ParseExceptionResult.class).ifPresent(e -> {
            if (firstWarningLogged.compareAndSet(false, true)) {
                logger.warn("There were problems parsing some source files, run with --info to see full stack traces");
            }
            logger.warn("There were problems parsing {}", source.getSourcePath());
            logger.debug(e.getMessage());
        });
        return source;
    }

    static boolean isExcluded(@Nullable Repository repository, Collection<PathMatcher> exclusions, Path path) {
        for (PathMatcher excluded : exclusions) {
            if (excluded.matches(path)) {
                return true;
            }
        }
        // PathMather will not evaluate the path "build.gradle" to be matched by the pattern "**/build.gradle"
        // This is counter-intuitive for most users and would otherwise require separate exclusions for files at the root and files in subdirectories
        if (!path.isAbsolute() && !path.startsWith(File.separator)) {
            return isExcluded(repository, exclusions, Paths.get("/" + path));
        }

        if (repository != null) {
            String repoRelativePath = PathUtils.separatorsToUnix(path.toString());
            if (repoRelativePath.isEmpty() || "/".equals(repoRelativePath)) {
                return false;
            }

            try (TreeWalk walk = new TreeWalk(repository)) {
                walk.addTree(new FileTreeIterator(repository));
                walk.setFilter(PathFilterGroup.createFromStrings(repoRelativePath));
                while (walk.next()) {
                    WorkingTreeIterator workingTreeIterator = walk.getTree(0, WorkingTreeIterator.class);
                    if (walk.getPathString().equals(repoRelativePath)) {
                        return workingTreeIterator.isEntryIgnored();
                    }
                    if (workingTreeIterator.getEntryFileMode().equals(FileMode.TREE)) {
                        if (workingTreeIterator.isEntryIgnored()) {
                            return true;
                        }
                        walk.enterSubtree();
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
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
        if ("org.openrewrite.Recipe$Noop".equals(recipe.getName())) {
            logger.warn("No recipes were activated. Activate a recipe with rewrite.activeRecipe(\"com.fully.qualified.RecipeClassName\") in your build file, or on the command line with -DactiveRecipe=com.fully.qualified.RecipeClassName");
            return new ResultsContainer(baseDir, null);
        }
        logger.lifecycle("Validating active recipes");
        Collection<Validated<Object>> validated = recipe.validateAll(ctx, new ArrayList<>());
        List<Validated.Invalid<Object>> failedValidations = validated.stream().map(Validated::failures)
                .flatMap(Collection::stream).collect(toList());
        if (!failedValidations.isEmpty()) {
            failedValidations.forEach(failedValidation -> logger.error(
                    "Recipe validation error in {} for property {}: {}",
                    recipe.getName(),
                    failedValidation.getProperty(),
                    failedValidation.getMessage(),
                    failedValidation.getException()));
            if (extension.getFailOnInvalidActiveRecipes()) {
                throw new RuntimeException("Recipe validation errors detected as part of one or more activeRecipe(s). Please check error logs.");
            }
            logger.error("Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless.");
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
        Map<Class<? extends SourceFile>, NamedStyles> stylesByType = new HashMap<>();
        stylesByType.put(J.CompilationUnit.class, javaDetector.build());
        stylesByType.put(K.CompilationUnit.class, kotlinDetector.build());
        stylesByType.put(Xml.Document.class, xmlDetector.build());
        sourceFiles = ListUtils.map(sourceFiles, applyAutodetected(stylesByType));

        logger.lifecycle("All sources parsed, running active recipes: {}", String.join(", ", getActiveRecipes()));
        RecipeRun recipeRun = recipe.run(new InMemoryLargeSourceSet(sourceFiles), ctx);

        if (extension.isExportDatatables()) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
            Path datatableDirectoryPath = project.getLayout().getBuildDirectory().dir("reports/rewrite/datatables/" + timestamp).get().getAsFile().toPath();
            logger.info(String.format("Printing available datatables to: %s", datatableDirectoryPath));
            recipeRun.exportDatatablesToCsv(datatableDirectoryPath, ctx);
        }

        return new ResultsContainer(baseDir, recipeRun);
    }

    @Override
    public void shutdownRewrite() {
        REPO_ROOT_TO_PROVENANCE.clear();
        GradleProjectBuilder.clearCaches();
        if (repository != null) {
            repository.close();
        }
    }

    private UnaryOperator<SourceFile> applyAutodetected(
            Map<Class<? extends SourceFile>, NamedStyles> stylesByType) {
        return before -> {
            for (Map.Entry<Class<? extends SourceFile>, NamedStyles> styleTypeEntry : stylesByType.entrySet()) {
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
            for (NamedStyles style : getStyles()) {
                m = m.addIfAbsent(style);
            }
            return s.withMarkers(m);
        };
    }

    static <T extends SourceFile> UnaryOperator<T> addProvenance(Marker sourceSet) {
        return s -> {
            Markers m = s.getMarkers();
            m = m.addIfAbsent(sourceSet);
            return s.withMarkers(m);
        };
    }

    private <T extends SourceFile> UnaryOperator<T> addGitTreeEntryInformation() {
        return s -> {
            if (repository == null) {
                return s;
            }

            try {
                ObjectId head = repository.resolve("HEAD");
                if (head == null) {
                    return s;
                }

                try (RevWalk revWalk = new RevWalk(repository);
                     TreeWalk treeWalk = new TreeWalk(repository)) {
                    RevCommit commit = revWalk.parseCommit(head);
                    treeWalk.addTree(commit.getTree());
                    treeWalk.setRecursive(true);
                    treeWalk.setFilter(PathFilter.create(PathUtils.separatorsToUnix(s.getSourcePath().toString())));

                    if (treeWalk.next()) {
                        return s.withMarkers(s.getMarkers().add(new GitTreeEntry(randomId(), treeWalk.getObjectId(0).name(), treeWalk.getRawMode(0))));
                    }
                    return s;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
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

    private List<SourceSet> findGradleSourceSets(Project project) {
        List<SourceSet> sourceSets = emptyList();
        if (project.getGradle().getGradleVersion().compareTo("7.1") >= 0) {
            JavaPluginExtension javaPluginExtension = project.getExtensions().findByType(JavaPluginExtension.class);
            if (javaPluginExtension != null) {
                sourceSets = new ArrayList<>(javaPluginExtension.getSourceSets());
            }
        } else {
            //Using the older javaConvention because we need to support older versions of gradle.
            try {
                Method getConventionMethod = Project.class.getDeclaredMethod("getConvention");
                Class<?> conventionClass = Class.forName("org.gradle.api.plugins.Convention");
                Method findPluginMethod = conventionClass.getDeclaredMethod("findPlugin", Class.class);
                Class<?> javaPluginConventionClass = Class.forName("org.gradle.api.plugins.JavaPluginConvention");
                Method getSourceSetsMethod = javaPluginConventionClass.getDeclaredMethod("getSourceSets");
                Object javaConvention = findPluginMethod.invoke(getConventionMethod.invoke(project), javaPluginConventionClass);
                if (javaConvention != null) {
                    sourceSets = new ArrayList<>((SourceSetContainer) getSourceSetsMethod.invoke(javaConvention));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return sourceSets.stream().sorted(Comparator.comparingInt(sourceSet -> {
            if ("main".equals(sourceSet.getName())) {
                return 0;
            }
            if ("test".equals(sourceSet.getName())) {
                return 1;
            }
            return 2;
        })).collect(toList());
    }

    private JavaVersion getJavaVersion(@Nullable JavaCompile javaCompileTask) {
        String sourceCompatibility = "";
        String targetCompatibility = "";
        if (javaCompileTask != null) {
            sourceCompatibility = javaCompileTask.getSourceCompatibility();
            targetCompatibility = javaCompileTask.getTargetCompatibility();
        }
        return new JavaVersion(
                randomId(),
                System.getProperty("java.runtime.version"),
                System.getProperty("java.vm.vendor"),
                sourceCompatibility,
                targetCompatibility);

    }

    private Charset getSourceFileEncoding(@Nullable CompileOptions compileOptions) {
        if (compileOptions != null && compileOptions.getEncoding() != null) {
            return Charset.forName(compileOptions.getEncoding());
        }
        return Charset.defaultCharset();
    }
}

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
import org.gradle.api.internal.tasks.userinput.UserInputHandler;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.internal.service.ServiceRegistry;
import org.openrewrite.*;
import org.openrewrite.binary.Binary;
import org.openrewrite.config.Environment;
import org.openrewrite.config.OptionDescriptor;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.gradle.DefaultRewriteExtension;
import org.openrewrite.gradle.GradleParser;
import org.openrewrite.gradle.GradleProjectParser;
import org.openrewrite.gradle.RewriteExtension;
import org.openrewrite.gradle.isolated.ui.RecipeDescriptorTreePrompter;
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.ipc.http.HttpUrlConnectionSender;
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
import org.openrewrite.quark.Quark;
import org.openrewrite.remote.Remote;
import org.openrewrite.shaded.jgit.api.Git;
import org.openrewrite.style.GeneralFormatStyle;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.tree.ParsingExecutionContextView;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.*;
import static java.util.stream.Collectors.*;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.gradle.TimeUtils.prettyPrint;
import static org.openrewrite.internal.ListUtils.map;

@SuppressWarnings("unused")
public class DefaultProjectParser implements GradleProjectParser {
    private static final JavaTypeCache javaTypeCache = new JavaTypeCache();
    private static final String LOG_INDENT_INCREMENT = "    ";

    private final Logger logger = Logging.getLogger(DefaultProjectParser.class);
    protected final Path baseDir;
    protected final RewriteExtension extension;
    protected final Project project;
    private final List<Marker> sharedProvenance;

    private List<NamedStyles> styles;
    private Environment environment;

    public DefaultProjectParser(Project project, RewriteExtension extension) {
        this.baseDir = project.getRootProject().getProjectDir().toPath();
        this.extension = extension;
        this.project = project;

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


    // By accident, we were inconsistent with the names of these properties between this and the maven plugin
    // Check all variants of the name, preferring more-fully-qualified names
    @Nullable
    private String getPropertyWithVariantNames(String property) {
        String maybeProp = System.getProperty("rewrite." + property + "s");
        if(maybeProp == null) {
            maybeProp = System.getProperty("rewrite." + property);
        }
        if(maybeProp == null) {
            maybeProp = System.getProperty(property + "s");
        }
        if(maybeProp == null) {
            maybeProp = System.getProperty(property);
        }
        return maybeProp;
    }

    public SortedSet<String> getActiveRecipes() {
        String activeRecipe = getPropertyWithVariantNames("activeRecipe");
        if(activeRecipe == null) {
            return new TreeSet<>(extension.getActiveRecipes());
        }
        return new TreeSet<>(Arrays.asList(activeRecipe.split(",")));
    }

    public SortedSet<String> getActiveStyles() {
        String activeStyle = getPropertyWithVariantNames("activeStyle");
        if(activeStyle == null) {
            return new TreeSet<>(extension.getActiveStyles());
        }
        return new TreeSet<>(Arrays.asList(activeStyle.split(",")));
    }

    public SortedSet<String> getAvailableStyles() {
        return environment().listStyles().stream().map(NamedStyles::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public void discoverRecipes(boolean interactive, ServiceRegistry serviceRegistry) {
        Collection<RecipeDescriptor> availableRecipeDescriptors = this.listRecipeDescriptors();

        if (interactive) {
            logger.quiet("Entering interactive mode, Ctrl-C to exit...");
            UserInputHandler prompter = serviceRegistry.get(UserInputHandler.class);
            RecipeDescriptorTreePrompter treePrompter = new RecipeDescriptorTreePrompter(prompter);
            RecipeDescriptor rd = treePrompter.execute(availableRecipeDescriptors);
            writeRecipeDescriptor(rd);
        } else {
            Set<String> activeRecipes = getActiveRecipes();
            Set<String> availableStyles = getAvailableStyles();
            Set<String> activeStyles = getActiveStyles();

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

    @Override
    public Collection<Path> listSources() {
        // Use a sorted collection so that gradle input detection isn't thrown off by ordering
        ResourceParser rp = new ResourceParser(baseDir, project, extension);
        Set<Path> result;
        try {
            result = new TreeSet<>(rp.listSources(project.getProjectDir().toPath()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
    public void dryRun(Path reportPath, boolean dumpGcActivity, Consumer<Throwable> onError) {
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
                    dryRun(reportPath, listResults(ctx));
                    logWriter.flush();
                    logger.lifecycle("Wrote rewrite GC log: {}", rewriteGcLog.getAbsolutePath());
                } catch (IOException e) {
                    logger.error("Unable to write rewrite GC log", e);
                    throw new UncheckedIOException(e);
                }
            }
        } else {
            dryRun(reportPath, listResults(ctx));
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
    public void run(Consumer<Throwable> onError) {
        run(listResults(new InMemoryExecutionContext(onError)));
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
                        if(result.getAfter() instanceof Quark) {
                            // We don't know the contents of a Quark, but we can move it
                            Files.move(originalLocation, results.getProjectRoot().resolve(result.getAfter().getSourcePath()));
                        } else {
                            // On Mac this can return "false" even when the file was deleted, so skip the check
                            //noinspection ResultOfMethodCallIgnored
                            originalLocation.toFile().delete();
                            writeAfter(results.getProjectRoot(), result);
                        }
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
        Path targetPath = root.resolve(result.getAfter().getSourcePath());
        File targetFile = targetPath.toFile();
        if(!targetFile.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetFile.getParentFile().mkdirs();
        }
        if(result.getAfter() instanceof Binary) {
            try (FileOutputStream sourceFileWriter = new FileOutputStream(targetFile)) {
                sourceFileWriter.write(((Binary) result.getAfter()).getBytes());
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        } else if (result.getAfter() instanceof Remote) {
            Remote remote = (Remote) result.getAfter();
            try (FileOutputStream sourceFileWriter = new FileOutputStream(targetFile)) {
                InputStream source = remote.getInputStream(new HttpUrlConnectionSender());
                byte[] buf = new byte[4096];
                int length;
                while ((length = source.read(buf)) > 0) {
                    sourceFileWriter.write(buf, 0, length);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        } else {
            Charset charset = result.getAfter().getCharset() == null ? StandardCharsets.UTF_8 : result.getAfter().getCharset();
            try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(targetPath, charset)) {
                sourceFileWriter.write(new String(result.getAfter().printAll().getBytes(charset), charset));
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        }
        if(result.getAfter().getFileAttributes() != null) {
            FileAttributes fileAttributes = result.getAfter().getFileAttributes();
            if(targetFile.canRead() != fileAttributes.isReadable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setReadable(fileAttributes.isReadable());
            }
            if(targetFile.canWrite() != fileAttributes.isWritable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setWritable(fileAttributes.isWritable());
            }
            if(targetFile.canExecute() != fileAttributes.isExecutable()) {
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

            ResourceParser rp = new ResourceParser(baseDir, project, extension);
            Collection<PathMatcher> exclusions = extension.getExclusions().stream()
                    .map(pattern -> project.getProjectDir().toPath().getFileSystem().getPathMatcher("glob:" + pattern))
                    .collect(toList());
            List<SourceFile> sourceFiles = new ArrayList<>();
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
                    alreadyParsed.addAll(javaPaths);
                    Duration parseDuration = Duration.between(start, Instant.now());
                    logger.info("Finished parsing Java sources from {}/{} in {} ({} per source)",
                            project.getName(), sourceSet.getName(), prettyPrint(parseDuration), prettyPrint(parseDuration.dividedBy(javaPaths.size())));
                    cus = ListUtils.map(cus, cu -> {
                        if(isExcluded(exclusions, cu.getSourcePath())) {
                            return null;
                        }
                        return cu;
                    });
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
                        sourceFiles.addAll(map(rp.parse(resourcesDir.toPath(), alreadyParsed, ctx), addProvenance(projectProvenance, sourceSetProvenance)));
                    }
                }
            }

            if (extension.isEnableExperimentalGradleBuildScriptParsing()) {
                File buildScriptFile = subproject.getBuildFile();
                try {
                    if (buildScriptFile.toString().toLowerCase().endsWith(".gradle") && buildScriptFile.exists() && !isExcluded(exclusions, buildScriptFile.toPath())) {
                        GradleParser gradleParser = new GradleParser(
                                GroovyParser.builder()
                                        .styles(styles)
                                        .typeCache(javaTypeCache)
                                        .logCompilationWarningsAndErrors(false));
                        sourceFiles.addAll(gradleParser.parse(singleton(buildScriptFile.toPath()), baseDir, ctx));
                        alreadyParsed.add(buildScriptFile.toPath());
                    }
                } catch (Exception e) {
                    logger.warn("Problem with parsing gradle script at \"" + buildScriptFile.getAbsolutePath() + "\" : ", e);
                }
            }
            //Collect any additional yaml/properties/xml files that are NOT already in a source set.
            sourceFiles.addAll(map(rp.parse(subproject.getProjectDir().toPath(), alreadyParsed, ctx), addProvenance(projectProvenance, null)));
            sourceFiles.addAll(ParsingExecutionContextView.view(ctx).pollParseFailures());
            return sourceFiles;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
                    logger.warn("Unable to parse checkstyle configuration", e);
                }
            }
        }
        return styles;
    }

    @SuppressWarnings("unused")
    protected ResultsContainer listResults(ExecutionContext ctx) {
        Environment env = environment();
        Recipe recipe = env.activateRecipes(getActiveRecipes());
        if(recipe.getRecipeList().size() == 0) {
            logger.warn("No recipes were activated. " +
                    "Activate a recipe with rewrite.activeRecipe(\"com.fully.qualified.RecipeClassName\") in your build file, " +
                    "or on the command line with -DactiveRecipe=com.fully.qualified.RecipeClassName");
            return new ResultsContainer(baseDir, null);
        }
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

        List<SourceFile> sourceFiles = parse(ctx);

        logger.lifecycle("All sources parsed, running active recipes: {}", String.join(", ", getActiveRecipes()));
        RecipeRun recipeRun = recipe.run(sourceFiles, ctx);
        return new ResultsContainer(baseDir, recipeRun);
    }

    @Override
    public void shutdownRewrite() {
        J.clearCaches();
        Git.shutdown();
    }

    private List<J.CompilationUnit> maybeAutodetectStyles(List<J.CompilationUnit> sourceFiles, List<NamedStyles> styles) {
        ImportLayoutStyle importLayout = NamedStyles.merge(ImportLayoutStyle.class, styles);
        SpacesStyle spacesStyle = NamedStyles.merge(SpacesStyle.class, styles);
        TabsAndIndentsStyle tabsStyle = NamedStyles.merge(TabsAndIndentsStyle.class, styles);
        GeneralFormatStyle generalStyle = NamedStyles.merge(GeneralFormatStyle.class, styles);
        if(importLayout != null && spacesStyle != null && tabsStyle != null && generalStyle != null) {
            // No need to autodetect if all the styles it would detect are already present
            return sourceFiles;
        }
        Autodetect autodetect = Autodetect.detect(sourceFiles);
        return map(sourceFiles, cu -> cu.withMarkers(cu.getMarkers().add(autodetect)));
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

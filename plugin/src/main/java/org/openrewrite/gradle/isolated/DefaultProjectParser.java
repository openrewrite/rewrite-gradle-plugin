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
import org.gradle.api.internal.tasks.userinput.UserInputHandler;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.service.ServiceRegistry;
import org.openrewrite.*;
import org.openrewrite.binary.Binary;
import org.openrewrite.config.Environment;
import org.openrewrite.config.OptionDescriptor;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.gradle.GradleProjectParser;
import org.openrewrite.gradle.RewriteExtension;
import org.openrewrite.gradle.SanitizedMarkerPrinter;
import org.openrewrite.gradle.isolated.ui.RecipeDescriptorTreePrompter;
import org.openrewrite.gradle.parser.ProjectParser;
import org.openrewrite.gradle.toolingapi.GradleToolingApiProjectBuilder;
import org.openrewrite.gradle.toolingapi.parser.GradleProjectData;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.style.CheckstyleConfigLoader;
import org.openrewrite.java.tree.J;
import org.openrewrite.kotlin.tree.K;
import org.openrewrite.quark.Quark;
import org.openrewrite.remote.Remote;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.tree.ParsingEventListener;
import org.openrewrite.tree.ParsingExecutionContextView;
import org.openrewrite.xml.tree.Xml;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.util.stream.Collectors.*;

@SuppressWarnings("unused")
public class DefaultProjectParser implements GradleProjectParser {
    private static final String LOG_INDENT_INCREMENT = "    ";

    private static final Logger logger = Logging.getLogger(DefaultProjectParser.class);
    protected final RewriteExtension extension;
    protected final Project project;

    private ProjectParser projectParser;
    private List<NamedStyles> styles;
    private Environment environment;

    public DefaultProjectParser(Project project, RewriteExtension extension) {
        this.extension = extension;
        this.project = project;
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
        return getProjectParser().listSources();
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

                if (extension.getFailOnDryRunResults()) {
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

    private ProjectParser getProjectParser() {
        if (projectParser == null) {
            GradleProjectData projectData = GradleProjectData.create(project);
            projectParser = new ProjectParser(projectData, new ProjectParser.Options() {

                @Override
                public List<String> getExclusions() {
                    return extension.getExclusions();
                }

                @Override
                public boolean getLogCompilationWarningsAndErrors() {
                    return extension.getLogCompilationWarningsAndErrors();
                }

                @Override
                public List<String> getPlainTextMasks() {
                    return extension.getPlainTextMasks();
                }

                @Override
                public int getSizeThresholdMb() {
                    return extension.getSizeThresholdMb();
                }

                @Override
                public List<NamedStyles> getStyles() {
                    return DefaultProjectParser.this.getStyles();
                }
            }, logger);
        }
        return projectParser;
    }

    public Stream<SourceFile> parse(ExecutionContext ctx) {
        return getProjectParser().parse(ctx);
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
            return new ResultsContainer(getProjectParser().getBaseDir(), null);
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
        return new ResultsContainer(getProjectParser().getBaseDir(), recipeRun);
    }

    public void shutdownRewrite() {
        GradleToolingApiProjectBuilder.clearCaches();
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

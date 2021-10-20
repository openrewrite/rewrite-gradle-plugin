/*
 * Copyright 2021 the original author or authors.
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
package org.openrewrite.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.SourceSet;
import org.gradle.util.GradleVersion;
import org.openrewrite.gradle.RewriteReflectiveFacade.*;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static java.util.Collections.*;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public abstract class AbstractRewriteTask extends DefaultTask implements RewriteTask {

    private List<Project> projects;
    private RewriteExtension extension;
    private RewriteReflectiveFacade rewrite;
    private ResolveRewriteDependenciesTask resolveDependenciesTask;
    private static final Map<File, byte[]> astCache = new HashMap<>();
    protected boolean useAstCache;

    AbstractRewriteTask setProjects(List<Project> projects) {
        this.projects = projects;
        return this;
    }

    AbstractRewriteTask setExtension(RewriteExtension extension) {
        this.extension = extension;
        return this;
    }

    AbstractRewriteTask setResolveDependenciesTask(ResolveRewriteDependenciesTask resolveRewriteDependenciesTask) {
        this.resolveDependenciesTask = resolveRewriteDependenciesTask;
        this.dependsOn(resolveRewriteDependenciesTask);
        return this;
    }

    @Internal
    RewriteExtension getExtension() {
        return extension;
    }

    @Internal
    RewriteReflectiveFacade getRewrite() {
        if(rewrite == null) {
            rewrite = new RewriteReflectiveFacade(resolveDependenciesTask.getResolvedDependencies(), extension, this);
        }
        return rewrite;
    }

    @Internal
    protected abstract Logger getLog();

    @Input
    public SortedSet<String> getActiveRecipes() {
        String activeRecipeProp = System.getProperty("activeRecipe");
        if(activeRecipeProp == null) {
            return new TreeSet<>(extension.getActiveRecipes());
        } else {
            return new TreeSet<>(Collections.singleton(activeRecipeProp));
        }
    }

    @Input
    public SortedSet<String> getActiveStyles() {
        return new TreeSet<>(extension.getActiveStyles());
    }

    /**
     * The prefix used to left-pad log messages, multiplied per "level" of log message.
     */
    private static final int HOURS_PER_DAY = 24;
    private static final int MINUTES_PER_HOUR = 60;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR;
    private static final int SECONDS_PER_DAY = SECONDS_PER_HOUR * HOURS_PER_DAY;

    protected Environment environment() {
        Map<Object, Object> gradleProps = getProject().getProperties().entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue));

        Properties properties = new Properties();
        properties.putAll(gradleProps);

        EnvironmentBuilder env = getRewrite().environmentBuilder(properties)
                .scanRuntimeClasspath()
                .scanUserHome();
        List<Path> recipeJars = resolveDependenciesTask.getResolvedDependencies().stream()
                .map(File::toPath)
                .collect(toList());
        for(Path rewriteJar : recipeJars) {
            env.scanJar(rewriteJar);
        }

        File rewriteConfig = extension.getConfigFile();
        if (rewriteConfig.exists()) {
            try (FileInputStream is = new FileInputStream(rewriteConfig)) {
                YamlResourceLoader resourceLoader = getRewrite().yamlResourceLoader(is, rewriteConfig.toURI(), properties);
                env.load(resourceLoader);
            } catch (IOException e) {
                throw new RuntimeException("Unable to load rewrite configuration", e);
            }
        } else if (extension.getConfigFileSetDeliberately()) {
            getLog().warn("Rewrite configuration file " + rewriteConfig + " does not exist.");
        }

        return env.build();
    }

    protected InMemoryExecutionContext executionContext() {
        return getRewrite().inMemoryExecutionContext(t -> getLog().warn(t.getMessage(), t));
    }

    protected ResultsContainer listResults() {
        Path baseDir = getProject().getRootProject().getRootDir().toPath();
        Environment env = environment();
        Set<String> activeRecipes = getActiveRecipes();
        Set<String> activeStyles = getActiveStyles();
        getLog().lifecycle(String.format("Using active recipe(s) %s", activeRecipes));
        getLog().lifecycle(String.format("Using active styles(s) %s", activeStyles));
        if (activeRecipes.isEmpty()) {
            return new ResultsContainer(baseDir, emptyList());
        }
        List<NamedStyles> styles = env.activateStyles(activeStyles);
        File checkstyleConfig = extension.getCheckstyleConfigFile();
        if (checkstyleConfig != null && checkstyleConfig.exists()) {
            NamedStyles checkstyle = getRewrite().loadCheckstyleConfig(checkstyleConfig.toPath(), extension.getCheckstyleProperties());
            styles.add(checkstyle);
        }

        Recipe recipe = env.activateRecipes(activeRecipes);

        getLog().lifecycle("Validating active recipes");
        Collection<Validated> validated = recipe.validateAll();
        List<Validated.Invalid> failedValidations = validated.stream().map(Validated::failures)
                .flatMap(Collection::stream).collect(toList());
        if (!failedValidations.isEmpty()) {
            failedValidations.forEach(failedValidation -> getLog().error(
                    "Recipe validation error in " + failedValidation.getProperty() + ": " +
                            failedValidation.getMessage(), failedValidation.getException()));
            if (getExtension().getFailOnInvalidActiveRecipes()) {
                throw new RuntimeException("Recipe validation errors detected as part of one or more activeRecipe(s). Please check error logs.");
            } else {
                getLog().error("Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless.");
            }
        }

        List<SourceFile> sourceFiles;
        if (useAstCache && astCache.containsKey(getProject().getRootProject().getRootDir())) {
            getLog().lifecycle("Using cached in-memory ASTs...");
            sourceFiles = getRewrite().toSourceFile(astCache.get(getProject().getRootProject().getRootDir()));
        } else {
            InMemoryExecutionContext ctx = executionContext();
            sourceFiles = projects.stream()
                    .flatMap(p -> parse(p, styles, ctx).stream())
                    .collect(toList());
            if (useAstCache) {
                astCache.put(getProject().getRootProject().getRootDir(), getRewrite().toBytes(sourceFiles));
            }
        }
        getLog().lifecycle("Running recipe(s)...");
        List<Result> results = recipe.run(sourceFiles);

        return new ResultsContainer(baseDir, results);
    }

    protected void clearAstCache() {
        astCache.clear();
    }

    protected void shutdownRewrite() {
        rewrite.shutdown();
    }

    protected List<SourceFile> parse(Project subproject, List<NamedStyles> styles, InMemoryExecutionContext ctx) {
        try {
            Path baseDir = getProject().getRootProject().getRootDir().toPath();

            @SuppressWarnings("deprecation")
            JavaPluginConvention javaConvention = subproject.getConvention().findPlugin(JavaPluginConvention.class);

            JavaProjectProvenanceBuilder projectProvenanceBuilder = getRewrite().javaProjectProvenanceBuilder()
                    .projectName(subproject.getName())
                    .buildToolVersion(GradleVersion.current().getVersion())
                    .vmRuntimeVersion(System.getProperty("java.runtime.version"))
                    .vmVendor(System.getProperty("java.vm.vendor"))
                    .publicationGroupId(subproject.getGroup().toString())
                    .publicationArtifactId(subproject.getName())
                    .publicationVersion(subproject.getVersion().toString());

            Set<SourceSet> sourceSets;
            if(javaConvention == null) {
                sourceSets = emptySet();
            } else {
                sourceSets = javaConvention.getSourceSets();
                projectProvenanceBuilder.sourceCompatibility(javaConvention.getSourceCompatibility().toString())
                        .targetCompatibility(javaConvention.getTargetCompatibility().toString());
            }

            List<Marker> projectProvenance = projectProvenanceBuilder.build();

            List<SourceFile> sourceFiles = new ArrayList<>();
            if(extension.isEnableExperimentalGradleBuildScriptParsing()) {
                File buildScriptFile = subproject.getBuildFile();
                try {
                    if (buildScriptFile.toString().toLowerCase().endsWith(".gradle") && buildScriptFile.exists()) {
                        GradleParser gradleParser = getRewrite().gradleParser(
                                getRewrite().groovyParserBuilder()
                                        .styles(styles)
                                        .logCompilationWarningsAndErrors(true));
                        sourceFiles.addAll(gradleParser.parse(singleton(buildScriptFile.toPath()), baseDir, ctx));
                    }
                } catch (Exception e) {
                    getLog().warn("Problem with parsing gradle script at \"" + buildScriptFile.getAbsolutePath()  + "\" : ", e);
                }
            }

            Set<Path> seenSourceFiles = new HashSet<>();
            for(SourceSet sourceSet : sourceSets) {

                List<Path> javaPaths = sourceSet.getAllJava().getFiles().stream()
                        .filter(it -> it.isFile() && it.getName().endsWith(".java"))
                        .map(File::toPath)
                        .map(AbstractRewriteTask::normalizePath)
                        .collect(toList());

                List<Path> dependencyPaths = sourceSet.getCompileClasspath().getFiles().stream()
                        .map(File::toPath)
                        .map(AbstractRewriteTask::normalizePath)
                        .collect(toList());

                Marker javaSourceSet = getRewrite().javaSourceSet(sourceSet.getName(), dependencyPaths, ctx);

                if(javaPaths.size() > 0) {
                    getLog().lifecycle("Parsing " + javaPaths.size() + " Java files from " + sourceSet.getAllJava().getSourceDirectories().getAsPath());
                    Instant start = Instant.now();
                    sourceFiles.addAll(map(getRewrite().javaParserFromJavaVersion()
                                    .relaxedClassTypeMatching(true)
                                    .styles(styles)
                                    .classpath(dependencyPaths)
                                    .logCompilationWarningsAndErrors(extension.getLogCompilationWarningsAndErrors())
                                    .build()
                                    .parse(javaPaths, baseDir, ctx),
                            addProvenance(projectProvenance, javaSourceSet)));
                    Instant end = Instant.now();
                    Duration duration = Duration.between(start, end);
                    getLog().lifecycle("Parsed " + javaPaths.size() + " Java files in " + prettyPrint(duration) + " (" + prettyPrint(duration.dividedBy(javaPaths.size())) + " per file)");
                }

                //Other resources in the source set, these will be marked with the Java Source set provenance information
                List<Path> yamlPaths = new ArrayList<>();
                List<Path> propertiesPaths = new ArrayList<>();
                List<Path> xmlPaths = new ArrayList<>();

                for (File file : sourceSet.getResources()) {
                    String fileName = file.getName().toLowerCase();
                    if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
                        yamlPaths.add(normalizePath(file.toPath()));
                    } else if (fileName.endsWith(".properties")) {
                        propertiesPaths.add(normalizePath(file.toPath()));
                    } else if (fileName.endsWith(".xml")) {
                        xmlPaths.add(normalizePath(file.toPath()));
                    }
                }

                if (yamlPaths.size() > 0) {
                    seenSourceFiles.addAll(yamlPaths);
                    getLog().lifecycle("Parsing " + yamlPaths.size() + " YAML files from " + sourceSet.getResources().getSourceDirectories().getAsPath());
                    Instant start = Instant.now();
                    sourceFiles.addAll(map(getRewrite().yamlParser().parse(yamlPaths, baseDir, ctx),
                            addProvenance(projectProvenance, javaSourceSet)));
                    Instant end = Instant.now();
                    Duration duration = Duration.between(start, end);
                    getLog().lifecycle("Parsed " + yamlPaths.size() + " YAML files in " + prettyPrint(duration) + " (" + prettyPrint(duration.dividedBy(yamlPaths.size())) + " per file)");
                }

                if (propertiesPaths.size() > 0) {
                    seenSourceFiles.addAll(propertiesPaths);
                    getLog().lifecycle("Parsing " + propertiesPaths.size() + " properties files from " + sourceSet.getResources().getSourceDirectories().getAsPath());
                    Instant start = Instant.now();
                    sourceFiles.addAll(map(getRewrite().propertiesParser().parse(propertiesPaths, baseDir, ctx),
                            addProvenance(projectProvenance, javaSourceSet)));

                    Instant end = Instant.now();
                    Duration duration = Duration.between(start, end);
                    getLog().lifecycle("Parsed " + propertiesPaths.size() + " properties files in " + prettyPrint(duration) + " (" + prettyPrint(duration.dividedBy(propertiesPaths.size())) + " per file)");
                }

                if (xmlPaths.size() > 0) {
                    seenSourceFiles.addAll(xmlPaths);
                    getLog().lifecycle("Parsing " + xmlPaths.size() + " XML files from " + sourceSet.getResources().getSourceDirectories().getAsPath());
                    Instant start = Instant.now();
                    sourceFiles.addAll(map(getRewrite().yamlParser().parse(yamlPaths, baseDir, ctx),
                            addProvenance(projectProvenance, javaSourceSet)));

                    Instant end = Instant.now();
                    Duration duration = Duration.between(start, end);
                    getLog().lifecycle("Parsed " + xmlPaths.size() + " XML files in " + prettyPrint(duration) + " (" + prettyPrint(duration.dividedBy(xmlPaths.size())) + " per file)");
                }
            }

            //Collect any additional yaml/properties/xml files that are NOT already in a source set.
            //We do not want to collect any of the files from sub-project folders, the build folder, or the "/.gradle"
            //folder.
            List<Path> yamlPaths = new ArrayList<>();
            List<Path> propertiesPaths = new ArrayList<>();
            List<Path> xmlPaths = new ArrayList<>();
            Set<Path> excludeDirectories = subproject.getSubprojects().stream()
                    .map(Project::getProjectDir)
                    .map(File::toPath)
                    .map(AbstractRewriteTask::normalizePath)
                    .collect(Collectors.toSet());
            excludeDirectories.add(normalizePath(subproject.getBuildDir().toPath()));
            excludeDirectories.add(normalizePath(subproject.getProjectDir().toPath().resolve(".gradle")));

            Files.walk(subproject.getProjectDir().toPath())
                    .map(AbstractRewriteTask::normalizePath)
                    .forEach(file -> {
                        if (Files.isDirectory(file) || seenSourceFiles.contains(file)) {
                            return;
                        }
                        for (Path exclude : excludeDirectories) {
                            if (file.startsWith(exclude)) {
                                return;
                            }
                        }
                        String fileName = file.toString().toLowerCase();
                        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
                            yamlPaths.add(file);
                        } else if (fileName.endsWith(".properties")) {
                            propertiesPaths.add(file);
                        } else if (fileName.endsWith(".xml")) {
                            xmlPaths.add(file);
                        }
                    });

            if (yamlPaths.size() > 0) {
                getLog().lifecycle("Parsing " + yamlPaths.size() + " YAML files from " + subproject.getProjectDir());
                Instant start = Instant.now();
                sourceFiles.addAll(map(getRewrite().yamlParser().parse(yamlPaths, baseDir, ctx),
                        addProvenance(projectProvenance, null)));
                Instant end = Instant.now();
                Duration duration = Duration.between(start, end);
                getLog().lifecycle("Parsed " + yamlPaths.size() + " YAML files in " + prettyPrint(duration) + " (" + prettyPrint(duration.dividedBy(yamlPaths.size())) + " per file)");
            }

            if(propertiesPaths.size() > 0) {
                getLog().lifecycle("Parsing " + propertiesPaths.size() + " properties files from " + subproject.getProjectDir());
                Instant start = Instant.now();
                sourceFiles.addAll(map(getRewrite().propertiesParser().parse(propertiesPaths, baseDir, ctx),
                        addProvenance(projectProvenance, null)));

                Instant end = Instant.now();
                Duration duration = Duration.between(start, end);
                getLog().lifecycle("Parsed " + propertiesPaths.size() + " properties files in " + prettyPrint(duration) + " (" + prettyPrint(duration.dividedBy(propertiesPaths.size())) + " per file)");
            }

            if (xmlPaths.size() > 0) {
                getLog().lifecycle("Parsing " + xmlPaths.size() + " XML files from " + subproject.getProjectDir());
                Instant start = Instant.now();
                sourceFiles.addAll(map(getRewrite().yamlParser().parse(yamlPaths, baseDir, ctx),
                        addProvenance(projectProvenance, null)));

                Instant end = Instant.now();
                Duration duration = Duration.between(start, end);
                getLog().lifecycle("Parsed " + xmlPaths.size() + " XML files in " + prettyPrint(duration) + " (" + prettyPrint(duration.dividedBy(xmlPaths.size())) + " per file)");
            }

            return sourceFiles;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UnaryOperator<SourceFile> addProvenance(List<Marker> projectProvenance, @Nullable Marker sourceSet) {
        return s -> {
            List<Marker> markerList = new ArrayList<>(projectProvenance);
            if (sourceSet != null) {
                markerList.add(sourceSet);
            }
            s = s.withMarkers(s.getMarkers().addAll(markerList));
            return s;
        };
    }

    public static class ResultsContainer {
        final Path projectRoot;
        final List<Result> generated = new ArrayList<>();
        final List<Result> deleted = new ArrayList<>();
        final List<Result> moved = new ArrayList<>();
        final List<Result> refactoredInPlace = new ArrayList<>();

        public ResultsContainer(Path projectRoot, Collection<Result> results) {
            this.projectRoot = projectRoot;
            for (Result result : results) {
                if (result.getBefore() == null && result.getAfter() == null) {
                    // This situation shouldn't happen / makes no sense, log and skip
                    continue;
                }
                if (result.getBefore() == null && result.getAfter() != null) {
                    generated.add(result);
                } else if (result.getBefore() != null && result.getAfter() == null) {
                    deleted.add(result);
                } else if (result.getBefore() != null && !result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())) {
                    moved.add(result);
                } else {
                    refactoredInPlace.add(result);
                }
            }
        }

        public Path getProjectRoot() {
            return projectRoot;
        }

        public boolean isNotEmpty() {
            return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
        }
    }

    protected void logRecipesThatMadeChanges(Result result) {
        for (Recipe recipe : result.getRecipesThatMadeChanges()) {
            getLog().warn("    " + recipe.getName());
        }
    }

    private static Path normalizePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String prettyPrint(Duration duration) {
        StringBuilder result = new StringBuilder();
        long days = duration.getSeconds() / SECONDS_PER_DAY;
        boolean startedPrinting = false;
        if(days > 0) {
            startedPrinting = true;
            result.append(days);
            result.append(" day");
            if(days != 1) {
                result.append("s");
            }
            result.append(" ");
        }

        long hours =  duration.toHours() % 24;
        if(startedPrinting || hours > 0) {
            startedPrinting = true;
            result.append(hours);
            result.append(" hour");
            if(hours != 1) {
                result.append("s");
            }
            result.append(" ");
        }

        long minutes = (duration.getSeconds() / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR;
        if(startedPrinting || minutes > 0) {
            result.append(minutes);
            result.append(" minute");
            if(minutes != 1) {
                result.append("s");
            }
            result.append(" ");
        }

        long seconds = duration.getSeconds() % SECONDS_PER_MINUTE;
        if(startedPrinting || seconds > 0) {
            result.append(seconds);
            result.append(" second");
            if (seconds != 1) {
                result.append("s");
            }
            result.append(" ");
        }

        long millis = duration.getNano() / 1000_000;
        result.append(millis);
        result.append(" millisecond");
        if(millis != 1) {
            result.append("s");
        }

        return result.toString();
    }

    @SuppressWarnings("ConstantConditions")
    public static <T> List<T> map(List<T> ls, UnaryOperator<T> map) {
        if (ls == null || ls.isEmpty()) {
            return ls;
        }
        List<T> newLs = ls;
        for (int i = 0; i < ls.size(); i++) {
            T tree = ls.get(i);
            T newTree = map.apply(tree);
            if (newTree != tree) {
                if (newLs == ls) {
                    newLs = new ArrayList<>(ls);
                }
                newLs.set(i, newTree);
            }
        }
        if (newLs != ls) {
            //noinspection StatementWithEmptyBody
            while (newLs.remove(null)) ;
        }

        return newLs;
    }
}

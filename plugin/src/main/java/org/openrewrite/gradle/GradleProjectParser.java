package org.openrewrite.gradle;

import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.openrewrite.*;
import org.openrewrite.config.Environment;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.gradle.ExampleRewriteTask.ResultsContainer;
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.java.style.CheckstyleConfigLoader;
import org.openrewrite.marker.BuildTool;
import org.openrewrite.marker.GitProvenance;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;
import org.openrewrite.style.NamedStyles;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.singleton;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.internal.ListUtils.map;

public class GradleProjectParser {
    private final Logger logger = Logging.getLogger(GradleProjectParser.class);
    private final Path baseDir;
    private final Collection<Path> classpath;
    private final RewriteExtension extension;
    private final Project rootProject;
    private final List<Marker> sharedProvenance;
    private final Boolean useAstCache;
    private static final Map<Path, List<SourceFile>> astCache = new HashMap<>();

    private Environment environment = null;

    public GradleProjectParser(Project rootProject, Boolean useAstCache, Collection<Path> recipeClasspath) {
        this.baseDir = rootProject.getRootDir().toPath();
        this.classpath = recipeClasspath;
        this.extension = rootProject.getExtensions().getByType(RewriteExtension.class);
        this.rootProject = rootProject;
        this.useAstCache = useAstCache;

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

    public Environment environment() {
        if(environment == null) {
            Map<Object, Object> gradleProps = rootProject.getProperties().entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                    .collect(toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue));

            Properties properties = new Properties();
            properties.putAll(gradleProps);

            Environment.Builder env = Environment.builder();
            for(Path jar : classpath) {
                try {
                    if(!jar.toString().contains("rewrite-core")) {
                        env.scanJar(jar, GradleProjectParser.class.getClassLoader());
                    }
                } catch (Exception e) {
                    logger.warn("Unable to load recipes from {}. {}", jar, e);
                }
            }
            File rewriteConfig = extension.getConfigFile();
            if (rewriteConfig.exists()) {
                try (FileInputStream is = new FileInputStream(rewriteConfig)) {
                    YamlResourceLoader resourceLoader = new YamlResourceLoader(is, rewriteConfig.toURI(), properties);
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

    @SuppressWarnings("unused")
    public List<SourceFile> parse() {
        Environment env = environment();
        ExecutionContext ctx = new InMemoryExecutionContext(t -> logger.warn(t.getMessage(), t));
        List<NamedStyles> styles = env.activateStyles(getActiveStyles());
        File checkstyleConfig = extension.getCheckstyleConfigFile();
        if (checkstyleConfig != null && checkstyleConfig.exists()) {
            try {
                styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(checkstyleConfig.toPath(), extension.getCheckstyleProperties()));
            } catch (Exception e) {
                logger.warn("Unable to parse checkstyle configuration", e);
            }
        }
        List<SourceFile> sourceFiles = new ArrayList<>();
        Set<Path> alreadyParsed = new HashSet<>();
        for(Project subProject : rootProject.getSubprojects()) {
            sourceFiles.addAll(parse(subProject, styles, alreadyParsed, ctx));
        }
        sourceFiles.addAll(parse(rootProject, styles, alreadyParsed, ctx));

        return sourceFiles;
    }

    public List<SourceFile> parse(Project subproject, List<NamedStyles> styles, Set<Path> alreadyParsed, ExecutionContext ctx) {
        try {
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

            ResourceParser2 rp = new ResourceParser2();

            List<SourceFile> sourceFiles = new ArrayList<>();
            if(extension.isEnableExperimentalGradleBuildScriptParsing()) {
                File buildScriptFile = subproject.getBuildFile();
                try {
                    if (buildScriptFile.toString().toLowerCase().endsWith(".gradle") && buildScriptFile.exists()) {
                        GradleParser gradleParser = new GradleParser(
                                GroovyParser.builder()
                                        .styles(styles)
                                        .logCompilationWarningsAndErrors(true));

                        sourceFiles.addAll(gradleParser.parse(singleton(buildScriptFile.toPath()), baseDir, ctx));
                    }
                } catch (Exception e) {
                    logger.warn("Problem with parsing gradle script at \"" + buildScriptFile.getAbsolutePath()  + "\" : ", e);
                }
            }

            for(SourceSet sourceSet : sourceSets) {
                List<Path> javaPaths = sourceSet.getAllJava().getFiles().stream()
                        .filter(it -> it.isFile() && it.getName().endsWith(".java"))
                        .map(File::toPath)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .collect(toList());

                List<Path> dependencyPaths = sourceSet.getCompileClasspath().getFiles().stream()
                        .map(File::toPath)
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .collect(toList());

                Marker javaSourceSet = JavaSourceSet.build(sourceSet.getName(), dependencyPaths, ctx);
                if(javaPaths.size() > 0) {
                    sourceFiles.addAll(map(JavaParser.fromJavaVersion()
                                    .relaxedClassTypeMatching(true)
                                    .styles(styles)
                                    .classpath(dependencyPaths)
                                    .logCompilationWarningsAndErrors(extension.getLogCompilationWarningsAndErrors())
                                    .build()
                                    .parse(javaPaths, baseDir, ctx),
                            addProvenance(projectProvenance, javaSourceSet)));
                }
                for (File resourcesDir : sourceSet.getResources().getSourceDirectories()) {
                    if(resourcesDir.exists()) {
                        sourceFiles.addAll(map(rp.parse(baseDir, resourcesDir.toPath(), alreadyParsed, ctx), addProvenance(projectProvenance, javaSourceSet)));
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

    @SuppressWarnings("unused")
    public ResultsContainer listResults() {
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
        if(useAstCache && astCache.containsKey(rootProject.getProjectDir().toPath())) {
            logger.lifecycle("Using cached in-memory ASTs");
            sourceFiles = astCache.get(rootProject.getProjectDir().toPath());
        } else {
            sourceFiles = parse();
            if(useAstCache) {
                astCache.put(rootProject.getProjectDir().toPath(), sourceFiles);
            }
        }
        List<Result> results = recipe.run(sourceFiles);
        return new ResultsContainer(baseDir, results);
    }

    public void clearAstCache() {
        astCache.clear();
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
}

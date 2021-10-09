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
package org.openrewrite.gradle;

import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;

import javax.annotation.Nullable;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * Provides access to Rewrite classes resolved and loaded from the supplied dependency configuration.
 * This keeps them isolated from the rest of Gradle's runtime classpath.
 * So there shouldn't be problems with conflicting transitive dependency versions or anything like that.
 */
@SuppressWarnings({"unchecked", "UnusedReturnValue", "InnerClassMayBeStatic"})
public class RewriteReflectiveFacade {

    private final Configuration configuration;
    private final RewriteExtension extension;
    private final Task task;
    private URLClassLoader classLoader;

    //Lazily creating and caching method references
    private Method sourceFileGetMarkers = null;
    private Method sourceFileWithMarkers = null;
    private Method markersAddIfAbsent = null;

    public RewriteReflectiveFacade(Configuration configuration, RewriteExtension extension, Task task) {
        this.configuration = configuration;
        this.extension = extension;
        this.task = task;
    }

    protected URLClassLoader getClassLoader() {
        // Lazily populate classLoader so that the configuration and extension have a chance to be altered by the rest of the build.gradle
        if (classLoader == null) {
            DependencyHandler dependencies = task.getProject().getDependencies();
            String rewriteVersion = extension.getRewriteVersion();
            Dependency[] deps = Stream.concat(
                    configuration.getDependencies().stream(),
                    Stream.of(
                            dependencies.create("org.openrewrite:rewrite-core:" + rewriteVersion),
                            dependencies.create("org.openrewrite:rewrite-java:" + rewriteVersion),
                            dependencies.create("org.openrewrite:rewrite-java-11:" + rewriteVersion),
                            dependencies.create("org.openrewrite:rewrite-java-8:" + rewriteVersion),
                            dependencies.create("org.openrewrite:rewrite-xml:" + rewriteVersion),
                            dependencies.create("org.openrewrite:rewrite-yaml:" + rewriteVersion),
                            dependencies.create("org.openrewrite:rewrite-properties:" + rewriteVersion),
                            dependencies.create("org.openrewrite:rewrite-groovy:" + rewriteVersion),
                            dependencies.create("org.openrewrite:rewrite-gradle:" + rewriteVersion),
                            // Some rewrite classes use slf4j loggers (even though they probably shouldn't)
                            // Ideally this would be the same implementation used by Gradle at runtime
                            // But there are reflection and classpath shenanigans that make that one hard to get at
                            dependencies.create("org.slf4j:slf4j-simple:1.7.30"),

                            // This is an optional dependency of rewrite-java needed when projects also apply the checkstyle plugin
                            dependencies.create("com.puppycrawl.tools:checkstyle:" + extension.getCheckstyleToolsVersion())
                    )).toArray(Dependency[]::new);
            // Use a detached configuration so as to avoid dependency resolution customizations
            Configuration confWithRewrite = task.getProject().getConfigurations().detachedConfiguration(deps);

            URL[] jars = confWithRewrite.getFiles().stream()
                    .map(File::toURI)
                    .map(uri -> {
                        try {
                            return uri.toURL();
                        } catch (MalformedURLException e) {
                            throw new RuntimeException(e);
                        }
                    }).toArray(URL[]::new);
            classLoader = new URLClassLoader(jars);
        }
        return classLoader;
    }

    private List<SourceFile> parseBase(Object real, Iterable<Path> sourcePaths, Path baseDir, InMemoryExecutionContext ctx) {
        try {
            Class<?> executionContextClass = getClassLoader().loadClass("org.openrewrite.ExecutionContext");
            List<Object> results = (List<Object>) real.getClass()
                    .getMethod("parse", Iterable.class, Path.class, executionContextClass)
                    .invoke(real, sourcePaths, baseDir, ctx.real);
            return results.stream()
                    .map(SourceFile::new)
                    .collect(toList());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public GradleParser gradleParser(GroovyParserBuilder groovyParserBuilder) {
        try {
            Class<?> groovyParserBuilderClass = getClassLoader().loadClass("org.openrewrite.groovy.GroovyParser$Builder");
            return new GradleParser(getClassLoader().loadClass("org.openrewrite.gradle.GradleParser")
                    .getDeclaredConstructor(groovyParserBuilderClass)
                    .newInstance(groovyParserBuilder.real));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class GradleParser {
        private final Object real;

        private GradleParser(Object real) {
            this.real = real;
        }

        public List<SourceFile> parse(Iterable<Path> sourcePaths, Path baseDir, InMemoryExecutionContext ctx) {
            return parseBase(real, sourcePaths, baseDir, ctx);
        }
    }

    public class GroovyParser {
        private final Object real;

        private GroovyParser(Object real) {
            this.real = real;
        }

        public List<SourceFile> parse(Iterable<Path> sourcePaths, Path baseDir, InMemoryExecutionContext ctx) {
            return parseBase(real, sourcePaths, baseDir, ctx);
        }
    }

    public GroovyParserBuilder groovyParserBuilder() {
        try {
            return new GroovyParserBuilder(getClassLoader()
                    .loadClass("org.openrewrite.groovy.GroovyParser")
                    .getMethod("builder")
                    .invoke(null));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public class GroovyParserBuilder {
        private final Object real;

        private GroovyParserBuilder(Object real) {
            this.real = real;
        }

        public GroovyParserBuilder styles(List<NamedStyles> styles) {
            try {
                List<Object> unwrappedStyles = styles.stream()
                        .map(it -> it.real)
                        .collect(toList());
                real.getClass().getMethod("styles", Iterable.class).invoke(real, unwrappedStyles);
                return this;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public GroovyParserBuilder classpath(Collection<Path> classpath) {
            try {
                real.getClass().getMethod("classpath", Collection.class).invoke(real, classpath);
                return this;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public GroovyParserBuilder logCompilationWarningsAndErrors(boolean logCompilationWarningsAndErrors) {
            try {
                real.getClass().getMethod("logCompilationWarningsAndErrors", boolean.class).invoke(real, logCompilationWarningsAndErrors);
                return this;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public GroovyParser build() {
            try {
                return new GroovyParser(real.getClass().getMethod("build").invoke(real));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class EnvironmentBuilder {
        private final Object real;

        private EnvironmentBuilder(Object real) {
            this.real = real;
        }

        public EnvironmentBuilder scanRuntimeClasspath(String... acceptPackages) {
            try {
                real.getClass().getMethod("scanRuntimeClasspath", String[].class).invoke(real, new Object[]{acceptPackages});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public EnvironmentBuilder scanJar(Path jar, ClassLoader classLoader) {
            try {
                real.getClass().getMethod("scanJar", Path.class, ClassLoader.class).invoke(real, jar, classLoader);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public EnvironmentBuilder scanJar(Path jar) {
            try {
                real.getClass().getMethod("scanJar", Path.class, ClassLoader.class).invoke(real, jar, classLoader);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public EnvironmentBuilder scanUserHome() {
            try {
                real.getClass().getMethod("scanUserHome").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public EnvironmentBuilder load(YamlResourceLoader yamlResourceLoader) {
            try {
                Class<?> resourceLoaderClass = getClassLoader().loadClass("org.openrewrite.config.ResourceLoader");
                real.getClass().getMethod("load", resourceLoaderClass).invoke(real, yamlResourceLoader.real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public Environment build() {
            try {
                return new Environment(real.getClass().getMethod("build").invoke(real));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class Marker {
        private final Object real;

        public Marker(Object real) { this.real = real; }
        Object getReal() {
            return real;
        }
    }

    public class Markers {
        final Object real;

        public Markers(Object real) {
            this.real = real;
        }
        public Markers addAll(List<Marker> markers) {
            try {
                Object newReal = real;
                for (Marker marker : markers) {
                    newReal = markersAddIfAbsentMethod().invoke(newReal, marker.getReal());
                }
                return newReal == real ? this : new Markers(newReal);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        private Method markersAddIfAbsentMethod() {
            if (markersAddIfAbsent == null) {
                try {
                    Class<?> sourceFileClass = getClassLoader().loadClass("org.openrewrite.marker.Markers");
                    markersAddIfAbsent = sourceFileClass.getMethod("addIfAbsent", getClassLoader().loadClass("org.openrewrite.marker.Marker"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return markersAddIfAbsent;
        }
    }

    public class SourceFile {
        private final Object real;

        private SourceFile(Object real) {
            this.real = real;
        }

        public Path getSourcePath() {
            try {
                return (Path) real.getClass().getMethod("getSourcePath").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String printAll() {
            try {
                return (String) real.getClass().getMethod("printAll").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Markers getMarkers() {
            try {
                return new Markers(sourceFileGetMarkersMethod().invoke(real));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        public SourceFile withMarkers(Markers markers) {
            try {
                return new SourceFile(sourceFileWithMarkersMethod().invoke(real, markers.real));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private Method sourceFileGetMarkersMethod() {
            if (sourceFileGetMarkers == null) {
                try {
                    Class<?> sourceFileClass = getClassLoader().loadClass("org.openrewrite.SourceFile");
                    sourceFileGetMarkers = sourceFileClass.getMethod("getMarkers");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return sourceFileGetMarkers;
        }
        private Method sourceFileWithMarkersMethod() {
            if (sourceFileWithMarkers == null) {
                try {
                    Class<?> sourceFileClass = getClassLoader().loadClass("org.openrewrite.SourceFile");
                    sourceFileWithMarkers = sourceFileClass.getMethod("withMarkers", getClassLoader().loadClass("org.openrewrite.marker.Markers"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return sourceFileWithMarkers;

        }

        public Object getReal() {
            return real;
        }
    }

    public Marker javaSourceSet(String sourceSetName, Iterable<Path> classpath, InMemoryExecutionContext ctx) {
        try {
            Class<?> executionContextClass = getClassLoader().loadClass("org.openrewrite.ExecutionContext");
            Class<?> c = getClassLoader().loadClass("org.openrewrite.java.marker.JavaSourceSet");
            Method javaSourceSetBuild = c.getMethod("build", String.class, Iterable.class, executionContextClass);

            return new Marker(javaSourceSetBuild.invoke(null, sourceSetName, classpath, ctx.real));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class JavaProjectProvenanceBuilder {
        private String projectName;

        //Build Tool
        private String buildToolVersion;

        //JavaVersion
        private String vmRuntimeVersion;
        private String vmVendor;
        private String sourceCompatibility;
        private String targetCompatibility;

        //Publication
        private String publicationGroupId;
        private String publicationArtifactId;
        private String publicationVersion;

        public JavaProjectProvenanceBuilder projectName(String projectName) {
            this.projectName = projectName;
            return this;
        }
        public JavaProjectProvenanceBuilder buildToolVersion(String buildToolVersion) {
            this.buildToolVersion = buildToolVersion;
            return this;
        }
        public JavaProjectProvenanceBuilder vmRuntimeVersion(String vmRuntimeVersion) {
            this.vmRuntimeVersion = vmRuntimeVersion;
            return this;
        }
        public JavaProjectProvenanceBuilder vmVendor(String vmVendor) {
            this.vmVendor = vmVendor;
            return this;
        }
        public JavaProjectProvenanceBuilder sourceCompatibility(String sourceCompatibility) {
            this.sourceCompatibility = sourceCompatibility;
            return this;
        }
        public JavaProjectProvenanceBuilder targetCompatibility(String targetCompatibility) {
            this.targetCompatibility = targetCompatibility;
            return this;
        }
        public JavaProjectProvenanceBuilder publicationGroupId(String publicationGroupId) {
            this.publicationGroupId = publicationGroupId;
            return this;
        }
        public JavaProjectProvenanceBuilder publicationArtifactId(String publicationArtifactId) {
            this.publicationArtifactId = publicationArtifactId;
            return this;
        }
        public JavaProjectProvenanceBuilder publicationVersion(String publicationVersion) {
            this.publicationVersion = publicationVersion;
            return this;
        }

        public List<Marker> build() {

            try {
                //Build Tool Type Enum
                @SuppressWarnings("rawtypes")
                Enum gradleType = Enum.valueOf ((Class<? extends Enum>) getClassLoader()
                        .loadClass("org.openrewrite.marker.BuildTool$Type"), "Gradle");

                Object buildTool = getClassLoader()
                        .loadClass("org.openrewrite.marker.BuildTool")
                        .getConstructor(UUID.class, gradleType.getClass(), String.class)
                        .newInstance(UUID.randomUUID(), gradleType, buildToolVersion);

                //Version
                Object javaVersion = getClassLoader()
                        .loadClass("org.openrewrite.java.marker.JavaVersion")
                        .getConstructor(UUID.class, String.class, String.class, String.class, String.class)
                        .newInstance(UUID.randomUUID(), this.vmRuntimeVersion, this.vmVendor, this.sourceCompatibility, this.targetCompatibility);

                Object publication = getClassLoader()
                        .loadClass("org.openrewrite.java.marker.JavaProject$Publication")
                        .getConstructor(String.class, String.class, String.class)
                        .newInstance(this.publicationGroupId, this.publicationArtifactId, this.publicationVersion);

                Object javaProject = getClassLoader()
                        .loadClass("org.openrewrite.java.marker.JavaProject")
                        .getConstructor(UUID.class, String.class, publication.getClass())
                        .newInstance(UUID.randomUUID(), projectName, publication);

                //Provenance
                return Arrays.asList(new Marker(buildTool), new Marker(javaVersion), new Marker(javaProject));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public JavaProjectProvenanceBuilder javaProjectProvenanceBuilder() {
        return new JavaProjectProvenanceBuilder();
    }

    public class Result {
        private final Object real;

        private Result(Object real) {
            this.real = real;
        }

        @Nullable
        public SourceFile getBefore() {
            try {
                return new SourceFile(real.getClass().getMethod("getBefore").invoke(real));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Nullable
        public SourceFile getAfter() {
            try {
                return new SourceFile(real.getClass().getMethod("getAfter").invoke(real));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public List<Recipe> getRecipesThatMadeChanges() {
            try {
                Set<Object> result = (Set<Object>) real.getClass().getMethod("getRecipesThatMadeChanges").invoke(real);
                return result.stream()
                        .map(Recipe::new)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String diff() {
            try {
                return (String) real.getClass().getMethod("diff").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class Recipe {
        private final Object real;

        private Recipe(Object real) {
            this.real = real;
        }

        public List<Result> run(List<SourceFile> sources) {
            try {
                List<Object> unwrappedSources = sources.stream().map(it -> it.real).collect(toList());
                List<Object> result = (List<Object>) real.getClass().getMethod("run", List.class)
                        .invoke(real, unwrappedSources);
                return result.stream()
                        .map(Result::new)
                        .collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public List<Result> run(List<SourceFile> sources, InMemoryExecutionContext ctx) {
            try {
                Class<?> executionContextClass = getClassLoader().loadClass("org.openrewrite.ExecutionContext");
                List<Object> unwrappedSources = sources.stream().map(it -> it.real).collect(toList());
                List<Object> result = (List<Object>) real.getClass().getMethod("run", List.class, executionContextClass)
                        .invoke(real, unwrappedSources, ctx.real);
                return result.stream()
                        .map(Result::new)
                        .collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String getName() {
            try {
                return (String) real.getClass().getMethod("getName").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Collection<Validated> validateAll() {
            try {
                List<Object> results = (List<Object>) real.getClass().getMethod("validateAll").invoke(real);
                return results.stream().map(r -> {
                    String canonicalName = r.getClass().getCanonicalName();
                    if (canonicalName.equals("org.openrewrite.Validated.Invalid")) {
                        return new Validated.Invalid(r);
                    } else if (canonicalName.equals("org.openrewrite.Validated.Both")) {
                        return new Validated.Both(r);
                    } else {
                        return null;
                    }
                }).filter(Objects::nonNull).collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }

    public interface Validated {

        Object getReal();

        default List<Invalid> failures() {
            try {
                Object real = getReal();
                List<Object> results = (List<Object>) real.getClass().getMethod("failures").invoke(real);
                return results.stream().map(Invalid::new).collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        class Invalid implements Validated {

            private final Object real;

            public Invalid(Object real) {
                this.real = real;
            }

            @Override
            public Object getReal() {
                return real;
            }

            public String getProperty() {
                try {
                    return (String) real.getClass().getMethod("getProperty").invoke(real);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            public String getMessage() {
                try {
                    return (String) real.getClass().getMethod("getMessage").invoke(real);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            public Throwable getException() {
                try {
                    return (Throwable) real.getClass().getMethod("getException").invoke(real);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        class Both implements Validated {

            private final Object real;

            public Both(Object real) {
                this.real = real;
            }

            @Override
            public Object getReal() {
                return real;
            }
        }
    }

    public class NamedStyles {
        private final Object real;

        private NamedStyles(Object real) {
            this.real = real;
        }

        public String getName() {
            try {
                return (String) real.getClass().getMethod("getName").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public NamedStyles loadCheckstyleConfig(Path checkstyleConf, Map<String, Object> properties) {
        try {
            Class<?> checkstyleClass = classLoader.loadClass("org.openrewrite.java.style.CheckstyleConfigLoader");
            Method method = checkstyleClass.getMethod("loadCheckstyleConfig", Path.class, Map.class);
            return new NamedStyles(method.invoke(null, checkstyleConf, properties));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class Environment {
        private final Object real;

        private Environment(Object real) {
            this.real = real;
        }

        public List<NamedStyles> activateStyles(Iterable<String> activeStyles) {
            try {
                //noinspection unchecked
                List<Object> raw = (List<Object>) real.getClass()
                        .getMethod("activateStyles", Iterable.class)
                        .invoke(real, activeStyles);
                return raw.stream()
                        .map(NamedStyles::new)
                        .collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Recipe activateRecipes(Iterable<String> activeRecipes) {
            try {
                return new Recipe(real.getClass()
                        .getMethod("activateRecipes", Iterable.class)
                        .invoke(real, activeRecipes));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Collection<RecipeDescriptor> listRecipeDescriptors() {
            try {
                Collection<Object> result = (Collection<Object>) real.getClass().getMethod("listRecipeDescriptors").invoke(real);
                return result.stream()
                        .map(RecipeDescriptor::new)
                        .collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Collection<NamedStyles> listStyles() {
            try {
                List<Object> raw = (List<Object>) real.getClass().getMethod("listStyles").invoke(real);
                return raw.stream()
                        .map(NamedStyles::new)
                        .collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }

    public class RecipeDescriptor {
        private final Object real;

        private RecipeDescriptor(Object real) {
            this.real = real;
        }

        public String getName() {
            try {
                return (String) real.getClass().getMethod("getName").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String getDisplayName() {
            try {
                return (String) real.getClass().getMethod("getDisplayName").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String getDescription() {
            try {
                return (String) real.getClass().getMethod("getDescription").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public List<OptionDescriptor> getOptions() {
            try {
                List<Object> results = (List<Object>) real.getClass().getMethod("getOptions").invoke(real);
                return results.stream().map(OptionDescriptor::new).collect(toList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class OptionDescriptor {
        private final Object real;

        private OptionDescriptor(Object real) {
            this.real = real;
        }

        public String getName() {
            try {
                return (String) real.getClass().getMethod("getName").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String getDisplayName() {
            try {
                return (String) real.getClass().getMethod("getDisplayName").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String getDescription() {
            try {
                return (String) real.getClass().getMethod("getDescription").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String getType() {
            try {
                return (String) real.getClass().getMethod("getType").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String getExample() {
            try {
                return (String) real.getClass().getMethod("getExample").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public boolean isRequired() {
            try {
                return (boolean) real.getClass().getMethod("isRequired").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }

    public EnvironmentBuilder environmentBuilder(Properties properties) {
        try {
            return new EnvironmentBuilder(getClassLoader()
                    .loadClass("org.openrewrite.config.Environment")
                    .getMethod("builder", Properties.class)
                    .invoke(null, properties)
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class YamlResourceLoader {
        private final Object real;

        private YamlResourceLoader(Object real) {
            this.real = real;
        }
    }

    public YamlResourceLoader yamlResourceLoader(InputStream yamlInput, URI source, Properties properties) {
        try {
            return new YamlResourceLoader(getClassLoader()
                    .loadClass("org.openrewrite.config.YamlResourceLoader")
                    .getConstructor(InputStream.class, URI.class, Properties.class)
                    .newInstance(yamlInput, source, properties)
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class InMemoryExecutionContext {
        private final Object real;

        private InMemoryExecutionContext(Object real) {
            this.real = real;
        }
    }

    public InMemoryExecutionContext inMemoryExecutionContext(Consumer<Throwable> onError) {
        try {
            return new InMemoryExecutionContext(getClassLoader()
                    .loadClass("org.openrewrite.InMemoryExecutionContext")
                    .getConstructor(Consumer.class)
                    .newInstance(onError));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class JavaParserBuilder {
        private final Object real;

        private JavaParserBuilder(Object real) {
            this.real = real;
        }

        public JavaParserBuilder styles(List<NamedStyles> styles) {
            try {
                List<Object> unwrappedStyles = styles.stream()
                        .map(it -> it.real)
                        .collect(toList());
                real.getClass().getMethod("styles", Iterable.class).invoke(real, unwrappedStyles);
                return this;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public JavaParserBuilder classpath(Collection<Path> classpath) {
            try {
                real.getClass().getMethod("classpath", Collection.class).invoke(real, classpath);
                return this;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public JavaParserBuilder logCompilationWarningsAndErrors(boolean logCompilationWarningsAndErrors) {
            try {
                real.getClass().getMethod("logCompilationWarningsAndErrors", boolean.class).invoke(real, logCompilationWarningsAndErrors);
                return this;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public JavaParserBuilder relaxedClassTypeMatching(boolean relaxedClassTypeMatching) {
            try {
                real.getClass().getMethod("relaxedClassTypeMatching", boolean.class).invoke(real, relaxedClassTypeMatching);
                return this;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public JavaParser build() {
            try {
                return new JavaParser(real.getClass().getMethod("build").invoke(real));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class JavaParser {
        private final Object real;

        private JavaParser(Object real) {
            this.real = real;
        }

        public List<SourceFile> parse(Iterable<Path> sourcePaths, Path baseDir, InMemoryExecutionContext ctx) {
            return parseBase(real, sourcePaths, baseDir, ctx);
        }
    }

    public JavaParserBuilder javaParserFromJavaVersion() {
        try {
            if (System.getProperty("java.version").startsWith("1.8")) {
                return new JavaParserBuilder(getClassLoader()
                        .loadClass("org.openrewrite.java.Java8Parser")
                        .getMethod("builder")
                        .invoke(null));
            }
            return new JavaParserBuilder(getClassLoader()
                    .loadClass("org.openrewrite.java.Java11Parser")
                    .getMethod("builder")
                    .invoke(null));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class YamlParser {
        private final Object real;

        private YamlParser(Object real) {
            this.real = real;
        }

        public List<SourceFile> parse(Iterable<Path> sourcePaths, Path baseDir, InMemoryExecutionContext ctx) {
            return parseBase(real, sourcePaths, baseDir, ctx);
        }
    }

    public YamlParser yamlParser() {
        try {
            return new YamlParser(getClassLoader().loadClass("org.openrewrite.yaml.YamlParser")
                    .getDeclaredConstructor()
                    .newInstance());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class PropertiesParser {
        private final Object real;

        private PropertiesParser(Object real) {
            this.real = real;
        }

        public List<SourceFile> parse(Iterable<Path> sourcePaths, Path baseDir, InMemoryExecutionContext ctx) {
            return parseBase(real, sourcePaths, baseDir, ctx);
        }
    }

    public PropertiesParser propertiesParser() {
        try {
            return new PropertiesParser(getClassLoader().loadClass("org.openrewrite.properties.PropertiesParser")
                    .getDeclaredConstructor()
                    .newInstance());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class XmlParser {
        private final Object real;

        private XmlParser(Object real) {
            this.real = real;
        }

        public List<SourceFile> parse(Iterable<Path> sourcePaths, Path baseDir, InMemoryExecutionContext ctx) {
            return parseBase(real, sourcePaths, baseDir, ctx);
        }
    }

    public XmlParser xmlParser() {
        try {
            return new XmlParser(getClassLoader().loadClass("org.openrewrite.xml.XmlParser")
                    .getDeclaredConstructor()
                    .newInstance());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    byte[] toBytes(List<SourceFile> sourceFiles) {
        List<Object> trees = sourceFiles.stream().map(s -> s.real).collect(Collectors.toList());
        try {
            Class<?> serializerClass = getClassLoader().loadClass("org.openrewrite.TreeSerializer");
            Object serializer = serializerClass.getDeclaredConstructor().newInstance();
            return (byte[]) serializerClass.getMethod("write", Iterable.class).invoke(serializer, trees);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    List<SourceFile> toSourceFile(byte[] trees) {
        try {
            Class<?> serializerClass = getClassLoader().loadClass("org.openrewrite.TreeSerializer");
            Object serializer = serializerClass.getDeclaredConstructor().newInstance();
            List<Object> sources = (List<Object>) serializerClass.getMethod("readList", byte[].class).invoke(serializer, trees);
            return sources.stream().map(SourceFile::new).collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void shutdown() {
        try {
            getClassLoader().loadClass("org.openrewrite.java.tree.J").getMethod("clearCaches").invoke(null);
            getClassLoader().loadClass("org.openrewrite.shaded.jgit.api.Git").getMethod("shutdown").invoke(null);
            getClassLoader().loadClass("org.openrewrite.scheduling.ForkJoinScheduler").getMethod("shutdown").invoke(null);
            classLoader = null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

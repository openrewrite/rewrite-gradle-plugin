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

import org.gradle.api.artifacts.Configuration;

import javax.annotation.Nullable;
import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

/**
 * Provides access to Rewrite classes resolved and loaded from the supplied dependency configuration.
 */
@SuppressWarnings({"unchecked", "UnusedReturnValue", "InnerClassMayBeStatic"})
public class RewriteReflectiveFacade {

    private final Configuration configuration;
    private URLClassLoader classLoader;

    public RewriteReflectiveFacade(Configuration configuration) {
        this.configuration = configuration;
    }

    private URLClassLoader getClassLoader() {
        // Lazily populate classLoader so that the configuration isn't resolved prematurely
        if (classLoader == null) {
            URL[] jars = configuration.getFiles().stream()
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

    public class EnvironmentBuilder {
        private final Object real;
        private EnvironmentBuilder(Object real) {
            this.real = real;
        }

        public EnvironmentBuilder scanRuntimeClasspath(String... acceptPackages) {
            try {
                real.getClass().getMethod("scanRuntimeClasspath", String[].class).invoke(real, new Object[]{ acceptPackages});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public EnvironmentBuilder scanClasspath(Iterable<Path> compileClasspath, String ... acceptPackages) {
            try {
                real.getClass().getMethod("scanClasspath", Iterable.class, String[].class)
                        .invoke(real, compileClasspath, acceptPackages);
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

    public class SourceFile {
        private final Object real;

        public SourceFile(Object real) {
            this.real = real;
        }

        public Path getSourcePath() {
            try {
                return (Path) real.getClass().getMethod("getSourcePath").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public String print() {
            try {
                return (String) real.getClass().getMethod("print").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class Result {
        private final Object real;

        public Result(Object real) {
            this.real = real;
        }

        @Nullable public SourceFile getBefore() {
            try {
                return new SourceFile(real.getClass().getMethod("getBefore").invoke(real));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Nullable public SourceFile getAfter() {
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

        public String diff(){
            try {
                return (String) real.getClass().getMethod("diff").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class Recipe {
        private final Object real;

        public Recipe(Object real) {
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

        public String getName() {
            try {
                return (String) real.getClass().getMethod("getName").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
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

        public RecipeDescriptor(Object real) {
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

        public YamlResourceLoader(Object real) {
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
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public class InMemoryExecutionContext {
        private final Object real;

        public InMemoryExecutionContext(Object real) {
            this.real = real;
        }
    }

    public InMemoryExecutionContext inMemoryExecutionContext(Consumer<Throwable> onError) {
        try {
            return new InMemoryExecutionContext(getClassLoader()
                    .loadClass("org.openrewrite.InMemoryExecutionContext")
                    .getConstructor(Consumer.class)
                    .newInstance(onError));
        } catch(Exception e) {
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
                return new JavaParserBuilder( getClassLoader()
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
        } catch(Exception e) {
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
        } catch(Exception e) {
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
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
}

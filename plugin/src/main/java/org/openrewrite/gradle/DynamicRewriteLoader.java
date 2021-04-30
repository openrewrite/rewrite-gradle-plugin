package org.openrewrite.gradle;

import org.gradle.api.artifacts.Configuration;
import org.openrewrite.java.JavaParser;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.yaml.YamlParser;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Provides access to Rewrite classes resolved & loaded from the supplied dependency configuration.
 */
public class DynamicRewriteLoader {

    private final Configuration configuration;
    private URLClassLoader classLoader;

    public DynamicRewriteLoader(Configuration configuration) {
        this.configuration = configuration;
    }

    private URLClassLoader getClassLoader() {
        // Lazily populate the class loader so that the configuration isn't resolved prematurely
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

    public class EnvironmentBuilder {
        private final Object real;
        private EnvironmentBuilder(Object real) {
            this.real = real;
        }

        public EnvironmentBuilder scanRuntimeClasspath() {
            try {
                real.getClass().getDeclaredMethod("scanRuntimeClasspath").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public EnvironmentBuilder scanClasspath(Iterable<Path> compileClasspath) {
            try {
                real.getClass().getDeclaredMethod("scanClasspath", Iterable.class, String[].class).invoke(real, compileClasspath, null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public EnvironmentBuilder scanUserHome() {
            try {
                real.getClass().getDeclaredMethod("scanUserHome").invoke(real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        public EnvironmentBuilder load(YamlResourceLoader yamlResourceLoader) {
            try {
                Class<?> resourceLoaderClass = getClassLoader().loadClass("org.openrewrite.config.ResourceLoader");
                real.getClass().getDeclaredMethod("load", resourceLoaderClass).invoke(real, yamlResourceLoader.real);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

    }

    public class SourceFile {
        private final Object real;

        public SourceFile(Object real) {
            this.real = real;
        }
    }

    public class Result {
        private final Object real;

        public Result(Object real) {
            this.real = real;
        }
    }

    public class Recipe {
        private final Object real;

        public Recipe(Object real) {
            this.real = real;
        }
    }

    public class Environment {
        private final Object real;

        public Environment(Object real) {
            this.real = real;
        }

        public List<Object> activateStyles(Iterable<String> activeStyles) {
            try {
                //noinspection unchecked
                return (List<Object>) real.getClass()
                        .getDeclaredMethod("activateStyles", Iterable.class)
                        .invoke(real, activeStyles);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public Recipe activateRecipes(Iterable<String> activeRecipes) {
            try {
                return (Recipe) real.getClass()
                        .getDeclaredMethod("activateRecipes", Iterable.class)
                        .invoke(real, activeRecipes);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public EnvironmentBuilder environmentBuilder(Properties properties) {
        try {
            return new EnvironmentBuilder(getClassLoader()
                    .loadClass("org.openrewrite.config.Environment")
                    .getDeclaredMethod("builder", Properties.class)
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

    public JavaParser.Builder<? extends JavaParser, ?> javaParserFromJavaVersion() {
        try {
            if (System.getProperty("java.version").startsWith("1.8")) {
                return (JavaParser.Builder<? extends JavaParser, ?>) getClassLoader()
                        .loadClass("org.openrewrite.java.Java8Parser")
                        .getDeclaredMethod("builder")
                        .invoke(null);
            }
            return (JavaParser.Builder<? extends JavaParser, ?>) getClassLoader()
                    .loadClass("org.openrewrite.java.Java11Parser")
                    .getDeclaredMethod("builder")
                    .invoke(null);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public YamlParser yamlParser() {
        try {
            return (YamlParser) getClassLoader().loadClass("org.openrewrite.yaml.YamlParser")
                    .getDeclaredConstructor()
                    .newInstance();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public PropertiesParser propertiesParser() {
        try {
            return (PropertiesParser) getClassLoader().loadClass("org.openrewrite.properties.PropertiesParser")
                    .getDeclaredConstructor()
                    .newInstance();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    public XmlParser xmlParser() {
        try {
            return (XmlParser) getClassLoader().loadClass("org.openrewrite.xml.XmlParser")
                    .getDeclaredConstructor()
                    .newInstance();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
}

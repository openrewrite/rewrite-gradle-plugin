package org.openrewrite.gradle;

import org.gradle.api.Project;
import org.openrewrite.SourceFile;
import org.openrewrite.config.Environment;
import org.openrewrite.gradle.ExampleRewriteTask.ResultsContainer;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

@SuppressWarnings("unchecked")
public class DelegatingProjectParser {
    private final Class<?> gppClass;
    private final Object gpp;

    public DelegatingProjectParser(Project rootProject, Set<Path> classpath, boolean useAstCache) {
        try {
            @SuppressWarnings("ConstantConditions")
            String path = DelegatingProjectParser.class
                    .getResource("/" + DelegatingProjectParser.class.getName().replace('.', '/') + ".class")
                    .toString();
            if(path.startsWith("jar:")) {
                path = path.substring(4);
            }
            int indexOfBang = path.indexOf("!");
            if(indexOfBang != -1) {
                path = path.substring(0, indexOfBang);
            }
            Path currentJar = Paths.get(new URI(path));

            classpath.add(currentJar);
            RewriteClassLoader cl = new RewriteClassLoader(classpath);

            gppClass = Class.forName("org.openrewrite.gradle.GradleProjectParser", true, cl);
            gpp = gppClass.getDeclaredConstructor(Project.class, Boolean.class, Collection.class)
                    .newInstance(rootProject, useAstCache, classpath);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public SortedSet<String> getActiveRecipes() {
        try {
            return (SortedSet<String>) gppClass.getMethod("getActiveRecipes").invoke(gpp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public SortedSet<String> getActiveStyles() {
        try {
            return (SortedSet<String>) gppClass.getMethod("getActiveStyles").invoke(gpp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Environment environment() {
        try {
            return (Environment) gppClass.getMethod("environment").invoke(gpp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<SourceFile> parse() {
        try {
            return (List<SourceFile>) gppClass.getMethod("parse").invoke(gpp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ResultsContainer listResults() {
        try {
            return (ResultsContainer) gppClass.getMethod("listResults").invoke(gpp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void clearAstCache() {
        try {
            gppClass.getMethod("clearAstCache").invoke(gpp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

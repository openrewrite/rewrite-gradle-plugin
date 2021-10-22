package org.openrewrite.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;
import org.openrewrite.SourceFile;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ExampleRewriteTask extends DefaultTask {
    private RewriteExtension extension;
    private ResolveRewriteDependenciesTask resolveDependenciesTask;

    @TaskAction
    void run() {
        try {
            String path = ExampleRewriteTask.class.getResource("/" + ExampleRewriteTask.class.getName().replace('.', '/') + ".class").toString();
            path = path.substring(4); // remove "jar:"
            int indexOfBang = path.indexOf("!");
            if(indexOfBang != -1) {
                path = path.substring(0, indexOfBang);
            }
            Path currentJar = Paths.get(new URI(path));


            Set<Path> classpath = resolveDependenciesTask.getResolvedDependencies().stream()
                    .map(File::toPath)
                    .collect(Collectors.toSet());
            classpath.add(currentJar);
            RewriteClassLoader cl = new RewriteClassLoader(classpath);

            Class<?> gppClass = Class.forName("org.openrewrite.gradle.GradleProjectParser", true, cl);
            Object gpp = gppClass
                    .getDeclaredConstructor(Path.class, Collection.class)
                    .newInstance(getProject().getRootDir().toPath(), classpath);

            List<SourceFile> sources = (List<SourceFile>) gppClass.getMethod("parse")
                            .invoke(gpp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ExampleRewriteTask setExtension(RewriteExtension extension) {
        this.extension = extension;
        return this;
    }

    public ExampleRewriteTask setResolveDependenciesTask(ResolveRewriteDependenciesTask resolveDependenciesTask) {
        this.resolveDependenciesTask = resolveDependenciesTask;
        this.dependsOn(resolveDependenciesTask);
        return this;
    }
}

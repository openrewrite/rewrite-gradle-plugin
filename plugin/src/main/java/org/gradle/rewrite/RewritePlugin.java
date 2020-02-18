package org.gradle.rewrite;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.compile.AbstractCompile;

public class RewritePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPlugins().withType(JavaPlugin.class, plugin -> {
            project.getTasks().create("lintSource", RewriteReportTask.class);
            project.getTasks().create("fixSourceLint", RewriteFixTask.class);

            project.getPlugins().withType(JavaBasePlugin.class, basePlugin -> {
                project.getTasks().withType(AbstractCompile.class, task -> {
                    // auto-fixing does not force compilation
                    project.getRootProject().getTasks().getByName("lintSource").dependsOn(task);
                    project.getRootProject().getTasks().getByName("fixSourceLint").dependsOn(task);
                });
            });
        });
    }
}

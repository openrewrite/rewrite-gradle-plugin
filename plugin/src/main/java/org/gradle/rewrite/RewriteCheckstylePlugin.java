package org.gradle.rewrite;

import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.internal.ConventionMapping;
import org.gradle.api.plugins.quality.CheckstylePlugin;
import org.gradle.api.plugins.quality.CodeQualityExtension;
import org.gradle.api.plugins.quality.internal.AbstractCodeQualityPlugin;
import org.gradle.api.resources.TextResource;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import java.io.File;
import java.util.Set;
import java.util.concurrent.Callable;

public class RewriteCheckstylePlugin extends AbstractCodeQualityPlugin<RewriteCheckstyleTask> {
    private RewriteExtension extension;

    @Override
    protected String getToolName() {
        return "Rewrite Checkstyle";
    }

    @Override
    protected Class<RewriteCheckstyleTask> getTaskType() {
        return RewriteCheckstyleTask.class;
    }

    @Override
    protected CodeQualityExtension createExtension() {
        extension = project.getExtensions().findByType(RewriteExtension.class);
        if(extension == null) {
            extension = project.getExtensions().create("rewrite", RewriteExtension.class, project);
        }
        extension.setToolVersion("2.0");
        return extension;
    }

    @Override
    protected void configureConfiguration(Configuration configuration) {
    }

    @Override
    protected void createConfigurations() {
        // don't need any configuration customization
    }

    @Override
    protected void configureTaskDefaults(RewriteCheckstyleTask task, String baseName) {
        configureTaskConventionMapping(task);
        configureReportsConventionMapping(task, baseName);
        runCheckstyleAfterRewriting();
    }

    protected void runCheckstyleAfterRewriting() {
        SourceSetContainer sourceSets = getJavaPluginConvention().getSourceSets();
        CheckstylePlugin checkstylePlugin = project.getPlugins().findPlugin(CheckstylePlugin.class);
        if (checkstylePlugin != null) {
            sourceSets.all(sourceSet -> {
                Task rewriteTask = project
                        .getTasksByName(sourceSet.getTaskName(getTaskBaseName(), null), false)
                        .iterator().next();

                Set<Task> checkstyleTasks = project.getTasksByName(sourceSet.getTaskName("checkstyle", null), false);
                if(!checkstyleTasks.isEmpty()) {
                    checkstyleTasks.iterator().next().shouldRunAfter(rewriteTask);
                }
            });
        }
    }

    @Override
    protected void configureForSourceSet(SourceSet sourceSet, RewriteCheckstyleTask task) {
        task.setDescription("Automatically fix Checkstyle issues for " + sourceSet.getName() + " sources");
        task.setSource(sourceSet.getAllJava());
    }

    private void configureTaskConventionMapping(RewriteCheckstyleTask task) {
        ConventionMapping taskMapping = task.getConventionMapping();
        taskMapping.map("config", (Callable<TextResource>) () -> extension.getCheckstyle().getConfig());
        taskMapping.map("ignoreFailures", (Callable<Boolean>) () -> extension.isIgnoreFailures());
        taskMapping.map("action", (Callable<RewriteAction>) () -> extension.getAction());
        taskMapping.map("excludeChecks", (Callable<Set<String>>) () -> extension.getExcludeChecks());
    }

    private void configureReportsConventionMapping(RewriteCheckstyleTask task, String baseName) {
        task.getReports().all(report -> {
            //noinspection UnstableApiUsage
            report.getRequired().convention(true);
            report.getOutputLocation().convention(project.getLayout().getProjectDirectory().file(project.provider(() ->
                    new File(extension.getReportsDir(), baseName + "." + report.getName()).getAbsolutePath())));
        });
    }
}

package org.gradle.rewrite;

import com.netflix.rewrite.parse.OpenJdkParser;
import com.netflix.rewrite.refactor.RefactorResult;
import com.netflix.rewrite.tree.visitor.refactor.RefactorVisitor;
import org.gradle.api.DefaultTask;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.plugins.quality.CheckstyleExtension;
import org.gradle.api.plugins.quality.CheckstylePlugin;
import org.gradle.rewrite.checkstyle.RewriteCheckstyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

public abstract class AbstractRewriteTask extends DefaultTask {
    private static final Logger logger = LoggerFactory.getLogger(AbstractRewriteTask.class);

    public List<RefactorResult> refactor() {
        JavaPluginConvention convention = getProject().getConvention().findPlugin(JavaPluginConvention.class);
        if (convention == null) {
            return emptyList();
        }

        List<RefactorVisitor> automaticallyAppliedRules = new ArrayList<>();

        if (getProject().getPlugins().hasPlugin(CheckstylePlugin.class)) {
            CheckstyleExtension extension = getProject().getExtensions().findByType(CheckstyleExtension.class);
            if (extension != null) {
                try (InputStream is = new FileInputStream(extension.getConfigFile())) {
                    automaticallyAppliedRules.addAll(RewriteCheckstyle.fromConfiguration(is));
                } catch (IOException e) {
                    logger.warn("Unable to read checkstyle configuration", e);
                }
            }
        }

        return convention.getSourceSets().stream().flatMap(ss -> {
            var parser = new OpenJdkParser(ss.getCompileClasspath().getFiles().stream().map(File::toPath).collect(toList()),
                    Charset.defaultCharset(), false);
            var cus = parser.parse(ss.getAllJava().getFiles().stream().map(File::toPath).collect(toList()));

            var refactorVisitors = new ArrayList<>(automaticallyAppliedRules);
            refactorVisitors.addAll(RewriteScanner.findRefactorVisitors(ss.getCompileClasspath()));

            return cus.stream()
                    .map(cu -> cu.refactor().visit(refactorVisitors).fix())
                    .filter(result -> !result.getRulesThatMadeChanges().isEmpty());
        }).collect(toList());
    }
}

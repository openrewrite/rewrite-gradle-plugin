package org.gradle.rewrite;

import com.netflix.rewrite.Parser;
import com.netflix.rewrite.RefactorResult;
import com.netflix.rewrite.visitor.refactor.RefactorVisitor;
import org.gradle.api.DefaultTask;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.plugins.quality.CheckstyleExtension;
import org.gradle.api.plugins.quality.CheckstylePlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.rewrite.checkstyle.RewriteCheckstyle;
import org.gradle.rewrite.spring.xml.AnnotationBasedBeanConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.xml.sax.InputSource;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
            var parser = new Parser(ss.getCompileClasspath().getFiles().stream().map(File::toPath).collect(toList()),
                    Charset.defaultCharset(), false);
            var cus = parser.parse(ss.getAllJava().getFiles().stream().map(File::toPath).collect(toList()));

            var refactorVisitors = new ArrayList<>(automaticallyAppliedRules);

            refactorVisitors.addAll(RewriteScanner.findRefactorVisitors(ss.getCompileClasspath()));

            refactorVisitors.addAll(addSpringAnnotationBeanConfigurationForSpringBeanXMLs(ss));

            return cus.stream()
                    .map(cu -> cu.refactor().visit(refactorVisitors).fix())
                    .filter(result -> !result.getRulesThatMadeChanges().isEmpty());
        }).collect(toList());
    }

    /**
     * If the project contains any Spring XML Bean configurations, migrate the project to annotation-driven configuration.
     */
    private List<RefactorVisitor> addSpringAnnotationBeanConfigurationForSpringBeanXMLs(SourceSet ss) {
        return ss.getResources().getFiles().stream()
                .filter(f -> f.getName().endsWith(".xml"))
                .filter(f -> {
                    var beanDefinitionReader = new XmlBeanDefinitionReader(new SimpleBeanDefinitionRegistry());
                    beanDefinitionReader.setValidating(false);

                    try(FileInputStream fis = new FileInputStream(f)) {
                        beanDefinitionReader.loadBeanDefinitions(new InputSource(fis));
                        return true;
                    } catch (IOException | BeanDefinitionStoreException ignored) {
                    }
                    return false;
                })
                .map(f -> {
                    try(FileInputStream fis = new FileInputStream(f)) {
                        return (RefactorVisitor) new AnnotationBasedBeanConfiguration(fis);
                    } catch (IOException ignored) {
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(toList());
    }
}

package org.gradle.rewrite;

import com.netflix.rewrite.RefactoringProvider;
import com.netflix.rewrite.tree.visitor.refactor.RefactorVisitor;
import eu.infomas.annotation.AnnotationDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;

public class RewriteScanner {
    private static final Logger logger = LoggerFactory.getLogger(RewriteScanner.class);

    private RewriteScanner() {
    }

    public static List<RefactorVisitor> findRefactorVisitors(Iterable<File> classpath) {
        var filteredClasspath = stream(classpath.spliterator(), false)
                .filter(f -> f.isDirectory() ||
                        f.getName().endsWith(".class") ||
                        (f.getName().endsWith(".jar") && !f.getName().endsWith("-javadoc.jar") && !f.getName().endsWith("-sources.jar")))
                .collect(toList());

        var classLoader = new URLClassLoader(
                filteredClasspath.stream()
                        .map(f -> {
                            try {
                                return f.toURI().toURL();
                            } catch (MalformedURLException e) {
                                throw new UncheckedIOException(e);
                            }
                        })
                        .toArray(URL[]::new),
                RewriteScanner.class.getClassLoader()
        );

        List<RefactorVisitor> refactorVisitors = new ArrayList<>();

        var reporter = new AnnotationDetector.MethodReporter() {
            @SuppressWarnings("unchecked")
            @Override
            public Class<? extends Annotation>[] annotations() {
                return new Class[]{RefactoringProvider.class};
            }

            @Override
            public void reportMethodAnnotation(Class<? extends Annotation> annotation, String className, String methodName) {
                try {
                    var clazz = Class.forName(className, true, classLoader);
                    Arrays.stream(clazz.getMethods())
                            .filter(m -> m.getName().equals(methodName) && m.isAnnotationPresent(RefactoringProvider.class))
                            .filter(m -> Modifier.isStatic(m.getModifiers()) &&
                                    m.getReturnType().equals(List.class) &&
                                    m.getParameterTypes().length == 0)
                            .flatMap(m -> {
                                try {
                                    //noinspection unchecked
                                    return ((List<RefactorVisitor>) m.invoke(clazz)).stream();
                                } catch (IllegalAccessException | InvocationTargetException | ClassCastException e) {
                                    logger.error("Failed to build refactor visitors from method {}#{}", className, methodName, e);
                                    return Stream.empty();
                                }
                            })
                            .forEach(refactorVisitors::add);
                } catch (ClassNotFoundException e) {
                    logger.error("Unable to check class {}", className, e);
                }
            }
        };

        try {
            new AnnotationDetector(reporter).detect(filteredClasspath.toArray(File[]::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return refactorVisitors;
    }
}

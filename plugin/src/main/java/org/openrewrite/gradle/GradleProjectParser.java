package org.openrewrite.gradle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.config.Environment;
import org.openrewrite.java.JavaParser;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class GradleProjectParser {
    private final Logger logger = Logging.getLogger(GradleProjectParser.class);
    private final Path baseDir;
    private final Collection<Path> classpath;

    public GradleProjectParser(Path baseDir, Collection<Path> classpath) {
        this.baseDir = baseDir;
        this.classpath = classpath;
    }

    public List<? extends SourceFile> parse() {
        Environment.Builder envb = Environment.builder();
        for(Path jar : classpath) {
            try {
                envb.scanJar(jar, GradleProjectParser.class.getClassLoader());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        Environment env = envb.build();
        JavaParser build = JavaParser.fromJavaVersion().build();

        return build.parse("public class A {}");
    }

    public List<Result> runRecipe() {


        return Collections.emptyList();
    }
}

class AstWriter {
    private GradleProjectParser gpp;

    public void writeAst(Path destination) {
        gpp.parse();
    }
}

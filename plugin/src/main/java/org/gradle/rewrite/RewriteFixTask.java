package org.gradle.rewrite;

import com.netflix.rewrite.RefactorResult;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class RewriteFixTask extends AbstractRewriteTask {
    @TaskAction
    public void refactorSource() {
        StyledTextService textOutput = new StyledTextService(getServices());

        List<RefactorResult> results = refactor();

        for (RefactorResult result : results) {
            try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(result.getOriginal().getSourcePath()))) {
                writer.write(result.getFixed().print());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        if (!results.isEmpty()) {
            textOutput.withStyle(Styling.Red).text("\u2716 Your source code requires refactoring. ")
                    .println("Please review changes and commit.");

            for (RefactorResult result : results) {
                textOutput.withStyle(Styling.Bold).println(getProject().getProjectDir().toPath().relativize(
                        new File(result.getOriginal().getSourcePath()).toPath()));
                result.getRulesThatMadeChanges().stream()
                        .sorted()
                        .forEach(rule -> textOutput.println("  " + rule));
            }
        }
    }
}

package org.gradle.rewrite;

import com.netflix.rewrite.RefactorResult;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.util.List;

public class RewriteReportTask extends AbstractRewriteTask {
    @TaskAction
    public void reportOnAvailableRefactorings() {
        StyledTextService textOutput = new StyledTextService(getServices());

        List<RefactorResult> results = refactor();

        if (!results.isEmpty()) {
            textOutput.withStyle(Styling.Red).text("\u2716 Your source code requires refactoring. ");
            textOutput.text("Run").withStyle(Styling.Bold).text("./gradlew fixSourceLint");
            textOutput.println(" to automatically fix.");

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

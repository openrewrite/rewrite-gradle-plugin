package org.openrewrite.gradle;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.TaskAction;
import org.openrewrite.RefactorPlan;
import org.openrewrite.RefactorVisitor;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public class RewriteDiscoverTask extends AbstractRewriteTask {
    private static Logger log = Logging.getLogger(RewriteDiscoverTask.class);
    @Override
    protected Logger getLog() {
        return log;
    }

    @TaskAction
    public void run() {
        RefactorPlan plan = plan();

        Set<String> activeRecipes = getActiveRecipes();
        // Print active recipes via log.quiet("message to print")
        log.quiet("");

        List<GradleRecipeConfiguration> recipes = getRecipes();
        for(GradleRecipeConfiguration recipe : recipes) {
            // Print recipe name
            Collection<RefactorVisitor<?>> visitors = plan.visitors(recipe.name);
            for(RefactorVisitor<?> visitor : visitors) {
                // Print visitor name
                log.quiet(visitor.toString());
            }
        }
    }
}

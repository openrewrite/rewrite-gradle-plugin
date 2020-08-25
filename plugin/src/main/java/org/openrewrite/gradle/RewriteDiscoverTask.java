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
    public void execute() {
        RefactorPlan plan = plan();

        Set<String> activeRecipes = getActiveRecipes();
        List<GradleRecipeConfiguration> recipes = getRecipes();

        log.quiet("Found " + activeRecipes.size() + " active recipes and " + recipes.size() + " total recipes.\n");
        log.quiet("Active Recipe Names:\n");

        for(String activeRecipe : activeRecipes) {
            log.quiet("\t" + activeRecipe + "\n");
        }

        log.quiet("Recipes:\n");

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

/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.openrewrite.Environment;
import org.openrewrite.Recipe;

import javax.inject.Inject;
import java.util.Map;
import java.util.Set;

public class RewriteDiscoverTask extends AbstractRewriteTask {
    private static final Logger log = Logging.getLogger(RewriteDiscoverTask.class);
    @Override
    protected Logger getLog() {
        return log;
    }

    @Inject
    public RewriteDiscoverTask(SourceSet sourceSet, RewriteExtension extension) {
        super(sourceSet, extension);
        setGroup("rewrite");
        setDescription("Lists all available recipes and their visitors within the " + sourceSet.getName() + " SourceSet");
    }

    @TaskAction
    public void run() {
        Environment env = environment();
        Set<String> activeRecipes = getActiveRecipes();
        Map<String, Recipe> recipesByName = env.getRecipesByName();

        log.quiet("Found " + activeRecipes.size() + " active recipes and " + recipesByName.size() + " total recipes.\n");

        log.quiet("Active Recipe Names:");
        for(String activeRecipe : activeRecipes) {
            log.quiet("\t" + activeRecipe);
        }

        log.quiet("\nRecipes:");
        for(Recipe recipe : recipesByName.values()) {
            log.quiet("\tname: " + recipe.getName());
            log.quiet("\tinclude: ");
            recipe.getInclude().forEach( rec -> {
                log.quiet("\t\t" + rec.pattern().replace("\\", ""));
            });
            log.quiet("\texclude: ");
            recipe.getExclude().forEach( rec -> {
                log.quiet("\t\t" + rec.pattern().replace("\\", ""));
            });
            log.quiet("\tvisitors: ");
            env.visitors(recipe.getName()).forEach( rec -> {
                log.quiet("\t\t" + rec.getName());
            });
            log.quiet("");
        }
    }
}

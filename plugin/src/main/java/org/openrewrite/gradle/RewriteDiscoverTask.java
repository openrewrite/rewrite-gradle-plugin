/*
 * Copyright 2021 the original author or authors.
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

import org.gradle.api.internal.tasks.userinput.UserInputHandler;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.openrewrite.config.Environment;
import org.openrewrite.config.OptionDescriptor;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.gradle.ui.RecipeDescriptorTreePrompter;
import org.openrewrite.style.NamedStyles;

import javax.inject.Inject;
import java.util.Collection;
import java.util.Set;

public class RewriteDiscoverTask extends AbstractRewriteTask {
    private static final Logger log = Logging.getLogger(RewriteDiscoverTask.class);
    private static final String LOG_INDENT_INCREMENT = "    ";
    private boolean interactive;

    @Option(description = "Whether to enter an interactive shell to explore available recipes.", option = "interactive")
    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    @Input
    public boolean isInteractive() {
        return this.interactive;
    }


    @Inject
    public RewriteDiscoverTask() {
        setGroup("rewrite");
        setDescription("Lists all available recipes and their visitors");
    }

    @TaskAction
    public void run() {
        Environment env = environment();
        Collection<RecipeDescriptor> availableRecipeDescriptors = env.listRecipeDescriptors();

        if (interactive) {
            log.quiet("Entering interactive mode, Ctrl-C to exit...");
            UserInputHandler prompter = getServices().get(UserInputHandler.class);
            RecipeDescriptorTreePrompter treePrompter = new RecipeDescriptorTreePrompter(prompter);
            RecipeDescriptor rd = treePrompter.execute(availableRecipeDescriptors);
            writeRecipeDescriptor(rd);
        } else {
            Set<String> activeRecipes = getActiveRecipes();
            Collection<NamedStyles> availableStyles = env.listStyles();
            Set<String> activeStyles = getActiveStyles();

            log.quiet("Available Recipes:");
            for (RecipeDescriptor recipe : availableRecipeDescriptors) {
                log.quiet(indent(1, recipe.getName()));
            }

            log.quiet(indent(0, ""));
            log.quiet("Available Styles:");
            for (NamedStyles style : availableStyles) {
                log.quiet(indent(1, style.getName()));
            }

            log.quiet(indent(0, ""));
            log.quiet("Active Styles:");
            for (String style : activeStyles) {
                log.quiet(indent(1, style));
            }

            log.quiet(indent(0, ""));
            log.quiet("Active Recipes:");
            for (String activeRecipe : activeRecipes) {
                log.quiet(indent(1, activeRecipe));
            }

            log.quiet(indent(0, ""));
            log.quiet("Found " + availableRecipeDescriptors.size() + " available recipes and " + availableStyles.size() + " available styles.");
            log.quiet("Configured with " + activeRecipes.size() + " active recipes and " + activeStyles.size() + " active styles.");
        }

    }

    @SuppressWarnings("ConstantConditions")
    private void writeRecipeDescriptor(RecipeDescriptor rd) {
        log.quiet(indent(0, rd.getDisplayName()));
        log.quiet(indent(1, rd.getName()));
        if (rd.getDescription() != null && !rd.getDescription().isEmpty()) {
            log.quiet(indent(1, rd.getDescription()));
        }
        if (!rd.getOptions().isEmpty()) {
            log.quiet(indent(0, "options: "));
            for (OptionDescriptor od : rd.getOptions()) {
                log.quiet(indent(1, od.getName() + ": " + od.getType() + (od.isRequired() ? "!" : "")));
                if (od.getDescription() != null && !od.getDescription().isEmpty()) {
                    log.quiet(indent(2, od.getDescription()));
                }
            }
        }
        log.quiet("");
    }

    private static String indent(int indent, CharSequence content) {
        StringBuilder prefix = repeat(indent);
        return prefix.append(content).toString();
    }

    private static StringBuilder repeat(int repeat) {
        StringBuilder buffer = new StringBuilder(repeat * LOG_INDENT_INCREMENT.length());
        for (int i = 0; i < repeat; i++) {
            buffer.append(LOG_INDENT_INCREMENT);
        }
        return buffer;
    }
}

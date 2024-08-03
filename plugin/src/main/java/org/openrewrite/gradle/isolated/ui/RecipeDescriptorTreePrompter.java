/*
 * Copyright 2024 the original author or authors.
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
package org.openrewrite.gradle.isolated.ui;

import org.gradle.api.internal.tasks.userinput.UserInputHandler;
import org.openrewrite.config.RecipeDescriptor;

import java.util.Collection;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Interactive shell to select and explore available options in the environment.
 */
public class RecipeDescriptorTreePrompter {
    private static final String INDEX_TO_ANSWER_MAPPING_MSG = "[%s]: %s\n";
    private static final String AVAILABLE_OPTIONS_MSG = "Available options:\n";
    private static final String CHOOSE_NUMBER_PROMPT = "Choose a number (hint: enter to return to initial list)";
    private static final String INPUT_SELECTION_MUST_BE_NUMBER_PROMPT = "\nYour input selection must be a number, try again (hint: enter to return to initial list)";
    private static final String YOUR_SELECTION_NOT_IN_OPTIONS_PROMPT = "\nYour selection [%s] is not an option in the list, try again";
    private static final String HIGHEST_ROOT_NODE_PROMPT = "\nNo parent directory higher than this, try again";

    private final UserInputHandler prompter;

    public RecipeDescriptorTreePrompter(UserInputHandler prompter) {
        this.prompter = prompter;
    }

    private RecipeDescriptorTree select(RecipeDescriptorTree tree) {
        SortedMap<String, RecipeDescriptorTree> answerSet = new TreeMap<>();
        RecipeDescriptorTree selection = null;

        do {
            // build out set of selection choices
            StringBuilder query = new StringBuilder(AVAILABLE_OPTIONS_MSG);
            int counter = 0;

            for (RecipeDescriptorTree option : tree.getChildren().stream().sorted().collect(Collectors.toList())) {
                counter++;
                String answer = String.valueOf(counter);
                answerSet.put(answer, option);
                query.append(String.format(INDEX_TO_ANSWER_MAPPING_MSG, answer, option.getDisplayName()));
            }

            String prompted;
            query.append(CHOOSE_NUMBER_PROMPT);
            do {
                prompted = prompter.askQuestion(query.toString(), "");
                if (prompted.isEmpty()) {
                    if (tree.getParent() != null) {
                        // navigate back up a layer
                        return select(tree.getParent());
                    } else {
                        query.append(HIGHEST_ROOT_NODE_PROMPT);
                    }
                } else if (!isNumber(prompted)) {
                    // is your input actually a number?
                    query.append(INPUT_SELECTION_MUST_BE_NUMBER_PROMPT);
                } else if (answerSet.get(prompted) == null) {
                    // is your number input found in the set of options?
                    query.append(String.format(YOUR_SELECTION_NOT_IN_OPTIONS_PROMPT, prompted));
                }
            } while (answerSet.get(prompted) == null);
            selection = answerSet.get(prompted);
        } while (selection == null);

        if (!selection.getChildren().isEmpty()) {
            // if the selected tree has children, recurse
            return select(selection);
        }
        // the selection tree has no children, thus is a leaf, thus our final stop on the recursion train
        return selection;
    }

    private static boolean isNumber(String message) {
        try {
            Integer.parseInt(message);
        } catch (NumberFormatException e) {
            return false;
        }
        return true;
    }

    public RecipeDescriptor execute(Collection<RecipeDescriptor> recipeDescriptors) {
        RecipeDescriptorTree tree = new RecipeDescriptorTree();
        tree.addPath(recipeDescriptors);
        RecipeDescriptorTree selection = select(tree);
        return selection.getRecipeDescriptor();
    }

}

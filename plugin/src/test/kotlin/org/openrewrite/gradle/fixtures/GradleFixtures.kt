/*
 * Copyright 2025 the original author or authors.
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
package org.openrewrite.gradle.fixtures

import org.openrewrite.gradle.TestKitRepositories

class GradleFixtures {
    companion object {
        /**
         * The repositories every build launched by TestKit resolves `org.openrewrite` artifacts from,
         * indented to sit in a build script whose lines are indented [indent] spaces.
         */
        fun repositories(indent: Int): String = TestKitRepositories.block(indent)

        val REPOSITORIES: String = repositories(16)

        //language=groovy
        val REWRITE_BUILD_GRADLE = """
            plugins {
                id("java")
                id("org.openrewrite.rewrite")
            }

            ${repositories(12)}
        """
    }
}

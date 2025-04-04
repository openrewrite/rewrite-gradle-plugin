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

class JavaFixtures {
    companion object {
        //language=java
        val HELLO_WORLD_JAVA_INTERFACE = """
            package org.openrewrite.before;

            public interface HelloWorld {
                void sayHello();
            }
        """.trimIndent()

        //language=java
        val HELLO_WORLD_JAVA_CLASS = """
            package org.openrewrite.before;

            public class HelloWorldImpl implements HelloWorld {
                @Override
                public void sayHello() {
                    System.out.println("Hello world");
                }
            }
        """.trimIndent()

        //language=java
        val GOODBYE_WORLD_JAVA_INTERFACE = """
            package org.openrewrite.before;

            public interface GoodbyeWorld {
                void sayGoodbye();
            }
        """.trimIndent()

        //language=java
        val GOODBYE_WORLD_JAVA_CLASS = """
            package org.openrewrite.before;

            public class GoodbyeWorldImpl implements GoodbyeWorld {
                @Override
                public void sayGoodbye() {
                    System.out.println("Goodbye world");
                }
            }
        """.trimIndent()
    }
}

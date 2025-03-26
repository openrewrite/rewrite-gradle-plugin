package org.openrewrite.gradle.fixtures

class JavaFixtures {
    companion object {
        //language=java
        val HELLO_WORLD_JAVA_INTERFACE = """
            package org.openrewrite.before;

            public interface HelloWorld {
                void sayGoodbye();
            }
        """.trimIndent()

        //language=java
        val HELLO_WORLD_JAVA_CLASS = """
            package org.openrewrite.before;

            public class HelloWorldImpl implements HelloWorld {
                @Override
                public void sayGoodbye() {
                    System.out.println("Hello world");
                }
            }
        """.trimIndent()
    }
}
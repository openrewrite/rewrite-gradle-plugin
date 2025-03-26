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
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
package org.openrewrite.gradle.condition;

import org.gradle.util.GradleVersion;
import org.junit.platform.commons.util.Preconditions;

import java.lang.annotation.Annotation;
import java.util.function.Function;


abstract class AbstractGradleRangeCondition<A extends Annotation> extends BooleanExecutionCondition<A> {

    static final GradleVersion current = GradleVersion.version(System.getProperty("org.openrewrite.test.gradleVersion", GradleVersion.current().getVersion()));
    static final String ENABLED_ON_CURRENT_GRADLE = //
            "Enabled on Gradle version: " + current.getVersion();

    static final String DISABLED_ON_CURRENT_GRADLE = //
            "Disabled on Gradle version: " + current.getVersion();

    private final String annotationName;

    AbstractGradleRangeCondition(Class<A> annotationType, Function<A, String> customDisabledReason) {
        super(annotationType, ENABLED_ON_CURRENT_GRADLE, DISABLED_ON_CURRENT_GRADLE, customDisabledReason);
        this.annotationName = annotationType.getSimpleName();
    }

    protected final boolean isCurrentVersionWithinRange(String minGradle, String maxGradle) {
        GradleVersion min = minGradle.isEmpty() ? null : GradleVersion.version(minGradle);
        GradleVersion max = maxGradle.isEmpty() ? null : GradleVersion.version(maxGradle);

        // Users must provide at least one of min and max versions.
        Preconditions.condition(min != null || max != null, () -> "@%s's minimum or maximum value must be configured".formatted(
                this.annotationName));

        // Finally, we need to validate the effective minimum and maximum values.
        Preconditions.condition(min == null || max == null || min.compareTo(max) <= 0,
                () -> "@%s's minimum value [%s] must be less than or equal to its maximum value [%s]".formatted(
                        this.annotationName, min == null ? null : min.getVersion(), max == null ? null : max.getVersion()));

        return (min == null || min.compareTo(current) <= 0) && (max == null || max.compareTo(current) >= 0);
    }

}

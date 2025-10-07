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

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.Annotation;
import java.util.function.Function;

import static org.junit.jupiter.api.extension.ConditionEvaluationResult.disabled;
import static org.junit.jupiter.api.extension.ConditionEvaluationResult.enabled;
import static org.junit.platform.commons.support.AnnotationSupport.findAnnotation;

abstract class BooleanExecutionCondition<A extends Annotation> implements ExecutionCondition {

	protected final Class<A> annotationType;
	private final String enabledReason;
	private final String disabledReason;
	private final Function<A, String> customDisabledReason;

	BooleanExecutionCondition(Class<A> annotationType, String enabledReason, String disabledReason,
			Function<A, String> customDisabledReason) {

		this.annotationType = annotationType;
		this.enabledReason = enabledReason;
		this.disabledReason = disabledReason;
		this.customDisabledReason = customDisabledReason;
	}

	@Override
	public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
		return findAnnotation(context.getElement(), this.annotationType) //
				.map(annotation -> isEnabled(annotation) ? enabled(this.enabledReason)
						: disabled(this.disabledReason, this.customDisabledReason.apply(annotation))) //
				.orElseGet(this::enabledByDefault);
	}

	abstract boolean isEnabled(A annotation);

	private ConditionEvaluationResult enabledByDefault() {
		String reason = String.format("@%s is not present", this.annotationType.getSimpleName());
		return enabled(reason);
	}

}

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
package org.openrewrite.gradle.isolated;

import com.android.build.gradle.BaseExtension;
import org.gradle.api.JavaVersion;

import java.lang.reflect.Method;
import java.nio.charset.Charset;

/*
 * AGP versions less than 4 define CompileOptions in com.android.build.gradle.internal.CompileOptions where as
 * versions greater than 4 define it in com.android.build.api.dsl.CompileOptions. This class encapsulates fetching
 * CompileOptions using either type.
 */
class AndroidProjectCompileOptions {
    private final Charset encoding;
    private final String sourceCompatibility;
    private final String targetCompatibility;

    AndroidProjectCompileOptions(Charset encoding, String sourceCompatibility, String targetCompatibility) {
        this.encoding = encoding;
        this.sourceCompatibility = sourceCompatibility;
        this.targetCompatibility = targetCompatibility;
    }

    static AndroidProjectCompileOptions fromBaseExtension(BaseExtension baseExtension) throws ReflectiveOperationException {
        Object compileOptions = callMethod(baseExtension, "getCompileOptions");
        String fileEncoding = callMethod(compileOptions, "getEncoding");
        JavaVersion sourceCompatibilityVersion = callMethod(compileOptions, "getSourceCompatibility");
        JavaVersion targetCompatibilityVersion = callMethod(compileOptions, "getTargetCompatibility");
        return new AndroidProjectCompileOptions(
                Charset.forName(fileEncoding),
                sourceCompatibilityVersion.toString(),
                targetCompatibilityVersion.toString());
    }

    private static <T> T callMethod(Object obj, String methodName) throws ReflectiveOperationException {
        Method method = obj.getClass().getMethod(methodName);
        return (T) method.invoke(obj);
    }

    Charset getEncoding() {
        return encoding;
    }

    String getSourceCompatibility() {
        return sourceCompatibility;
    }

    String getTargetCompatibility() {
        return targetCompatibility;
    }
}

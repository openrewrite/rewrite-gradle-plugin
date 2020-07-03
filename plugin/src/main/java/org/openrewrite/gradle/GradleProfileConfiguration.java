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

import org.openrewrite.config.ProfileConfiguration;

import java.util.*;

import static java.util.stream.Collectors.toMap;

public class GradleProfileConfiguration {

    public String name;

    public final Set<String> include = new HashSet<>();

    public final Set<String> exclude = new HashSet<>();

    public final Set<String> extend = new HashSet<>();

    public final List<GradleProfileProperty> configure = new ArrayList<>();

    public ProfileConfiguration toProfileConfiguration() {
        ProfileConfiguration profile = new ProfileConfiguration();

        profile.setName(name);
        profile.setInclude(include);
        profile.setExclude(exclude);
        profile.setExtend(extend);
        profile.setConfigure(configure.stream()
                .collect(toMap(prop -> prop.visitor + "." + prop.key, prop -> prop.value)));

        return profile;
    }
}

package org.gradle.rewrite;

import io.micrometer.core.instrument.MeterRegistry;
import org.gradle.api.Task;

public interface RewriteTask extends Task {
    void setMeterRegistry(MeterRegistry registry);
}

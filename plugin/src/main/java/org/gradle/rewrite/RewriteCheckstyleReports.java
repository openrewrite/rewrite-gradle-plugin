package org.gradle.rewrite;

import org.gradle.api.reporting.ReportContainer;
import org.gradle.api.reporting.SingleFileReport;

public interface RewriteCheckstyleReports extends ReportContainer<SingleFileReport> {
    /**
     * A diff file that can be applied with <pre>{@code git apply}</pre>.
     */
    SingleFileReport getPatch();
}

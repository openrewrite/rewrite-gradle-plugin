package org.gradle.rewrite;

import org.gradle.api.Task;
import org.gradle.api.internal.CollectionCallbackActionDecorator;
import org.gradle.api.reporting.SingleFileReport;
import org.gradle.api.reporting.internal.TaskGeneratedSingleFileReport;
import org.gradle.api.reporting.internal.TaskReportContainer;

import javax.inject.Inject;

public class RewriteCheckstyleReportsImpl extends TaskReportContainer<SingleFileReport> implements RewriteCheckstyleReports {
    @Inject
    public RewriteCheckstyleReportsImpl(Task task, CollectionCallbackActionDecorator callbackActionDecorator) {
        super(SingleFileReport.class, task, callbackActionDecorator);

        add(TaskGeneratedSingleFileReport.class, "patch", task);
    }

    @Override
    public SingleFileReport getPatch() {
        return getByName("patch");
    }
}
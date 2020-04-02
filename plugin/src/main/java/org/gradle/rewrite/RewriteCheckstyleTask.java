package org.gradle.rewrite;

import groovy.lang.Closure;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.IllegalTodoFileModification;
import org.eclipse.jgit.lib.RebaseTodoLine;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Incubating;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileType;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.quality.CheckstyleExtension;
import org.gradle.api.reporting.Reporting;
import org.gradle.api.resources.TextResource;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.Optional;
import org.gradle.rewrite.checkstyle.RewriteCheckstyle;
import org.gradle.util.ClosureBackedAction;
import org.gradle.work.ChangeType;
import org.gradle.work.InputChanges;
import org.openrewrite.Change;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;

public class RewriteCheckstyleTask extends SourceTask implements VerificationTask, Reporting<RewriteCheckstyleReports> {
    private TextResource config;
    private Map<String, Object> configProperties = new LinkedHashMap<>();
    private boolean ignoreFailures;
    private boolean showViolations = true;
    private RewriteAction action = RewriteAction.FIX_SOURCE;
    private Set<String> excludeChecks = new HashSet<>();

    private final FileCollection stableSources = getProject().files((Callable<Object>) this::getSource);

    @Nullable
    private Git git;

    private final RewriteCheckstyleReports reports;

    public RewriteCheckstyleTask() {
        include("**/*.java");
        reports = getObjectFactory().newInstance(RewriteCheckstyleReportsImpl.class, this);

        try {
            git = Git.open(getProject().getRootDir());
        } catch (IOException e) {
            // not a Git project, that's fine!
        }
    }

    @Inject
    protected ObjectFactory getObjectFactory() {
        throw new UnsupportedOperationException();
    }

    @Override
    @Nested
    public RewriteCheckstyleReports getReports() {
        return reports;
    }

    @Override
    public RewriteCheckstyleReports reports(Closure closure) {
        return reports(new ClosureBackedAction<>(closure));
    }

    @Override
    public RewriteCheckstyleReports reports(Action<? super RewriteCheckstyleReports> configureAction) {
        configureAction.execute(reports);
        return reports;
    }

    @TaskAction
    public void run(InputChanges inputChanges) throws IOException {
        TextResource config = this.config;
        Map<String, Object> configProperties = this.configProperties;

        CheckstyleExtension extension = getProject().getExtensions().findByType(CheckstyleExtension.class);
        if (extension != null) {
            if (config == null) {
                config = extension.getConfig();
            }
            if (configProperties == null) {
                configProperties = extension.getConfigProperties();
            }

            configProperties.put("config_loc", extension.getConfigDirectory().get().toString());
        }

        if (config == null || configProperties == null) {
            getLogger().debug("Not performing checkstyle remediation for project {}. Rewrite checkstyle must have the config property set or be able to retrieve it from the checkstyle extension",
                    getProject().getName());
            return;
        }

        List<Path> sourceChanges = stream(inputChanges.getFileChanges(getStableSources()).spliterator(), false)
                .filter(change -> !change.getChangeType().equals(ChangeType.REMOVED))
                .filter(change -> FileType.FILE.equals(change.getFileType()))
                .filter(change -> change.getFile().getName().endsWith(".java"))
                .map(change -> change.getFile().toPath())
                .collect(toList());

        if (sourceChanges.isEmpty()) {
            return;
        }

        RewriteCheckstyle rewrite = new RewriteCheckstyle(new ByteArrayInputStream(config.asString().getBytes(StandardCharsets.UTF_8)),
                getExcludes(),
                configProperties);

        getLogger().debug("Checking {} files for checkstyle auto-remediation", sourceChanges.size());

        JavaParser parser = new JavaParser(emptyList(), StandardCharsets.UTF_8, false);
        List<J.CompilationUnit> cus = parser.parse(sourceChanges, getProject().getRootDir().toPath());

        Status status = null;
        if (getAction().compareTo(RewriteAction.COMMIT) >= 0 && git != null) {
            try {
                status = git.status().call();
            } catch (GitAPIException e) {
                getLogger().warn("Rewrite checkstyle was unable to determine if there are any uncommitted changes " +
                        "and will not automatically commit any changes", e);
            }
        }

        List<Change<J.CompilationUnit>> changes = cus.stream()
                .map(cu -> rewrite.apply(cu.refactor()).fix())
                .filter(change -> !change.getRulesThatMadeChanges().isEmpty())
                .collect(toList());

        if (showViolations && !changes.isEmpty()) {
            for (Change<J.CompilationUnit> change : changes) {
                getLogger().warn("The following checkstyle rules in {} have been fixed:", change.getOriginal().getSourcePath());
                for (String rule : change.getRulesThatMadeChanges()) {
                    getLogger().warn("   {}", rule);
                }
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(reports.getPatch().getDestination().toPath())) {
            for (Change<J.CompilationUnit> change : changes) {
                writer.write(change.diff() + "\n");
                if (getAction().compareTo(RewriteAction.FIX_SOURCE) >= 0) {
                    try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(
                            getProject().getRootDir().toPath().resolve(change.getOriginal().getSourcePath()))) {
                        sourceFileWriter.write(change.getFixed().print());
                    }
                }
            }
        }

        if (getAction().compareTo(RewriteAction.COMMIT) >= 0 && git != null) {
            if (status == null) {
                getLogger().warn("Checkstyle violations have been fixed. Please review and commit the changes.");
            } else {
                AddCommand add = git.add();
                for (Change<J.CompilationUnit> change : changes) {
                    String filePattern = getProject().getRootProject().getRootDir().toPath().relativize(
                            getProject().getRootDir().toPath().resolve(change.getOriginal().getSourcePath())).toString();

                    // (1) If there are no uncommitted changes then we will proceed to a commit and rebase fixup.
                    // (2) If there are uncommitted changes then we will re-add any files with fixes to the index that were ALREADY
                    // in the index prior to running the fix task.
                    if (status.isClean() || !(status.getUntracked().contains(filePattern) || status.getModified().contains(filePattern))) {
                        add.addFilepattern(filePattern);
                    }
                }
                try {
                    add.call();

                    if (status.isClean()) {
                        boolean changedGpgSign = false;
                        StoredConfig gitConfig = git.getRepository().getConfig();
                        try {
                            if (gitConfig.getBoolean("commit", null, "gpgsign", false)) {
                                changedGpgSign = true;
                                gitConfig.setBoolean("commit", null, "gpgsign", true);
                                gitConfig.save();
                            }

                            List<RevCommit> lastTwoCommits = stream(git.log().setMaxCount(2).call().spliterator(), false).collect(toList());

                            if (lastTwoCommits.size() <= 1) {
                                getLogger().warn("Leaving checkstyle fixes as a separate commit because there is only one commit in the history, and " +
                                        "this plugin does not yet support rebasing the first two commits of a repository");
                            } else {
                                git.commit()
                                        .setMessage("Resolved checkstyle issues.")
                                        .call();

                                if(getAction().equals(RewriteAction.REBASE_FIXUP)) {
                                    git.rebase()
                                            .setOperation(RebaseCommand.Operation.BEGIN)
                                            .setUpstream(lastTwoCommits.get(1))
                                            .runInteractively(new RebaseCommand.InteractiveHandler() {
                                                @Override
                                                public void prepareSteps(List<RebaseTodoLine> list) {
                                                    try {
                                                        list.get(1).setAction(RebaseTodoLine.Action.FIXUP);
                                                    } catch (IllegalTodoFileModification e2) {
                                                        throw new IllegalStateException(e2);
                                                    }
                                                }

                                                @Override
                                                public String modifyCommitMessage(String oldMessage) {
                                                    return oldMessage;
                                                }
                                            })
                                            .call();
                                }
                            }
                        } finally {
                            if (changedGpgSign) {
                                gitConfig.setBoolean("commit", null, "gpgsign", true);
                            }
                        }
                    }
                } catch (GitAPIException e) {
                    getLogger().warn("Failed to automatically commit checkstyle fixes. Please review and commit the changes.", e);
                }
            }
        } else if (!changes.isEmpty()) {
            if (isIgnoreFailures()) {
                if (getAction().compareTo(RewriteAction.FIX_SOURCE) >= 0) {
                    getLogger().warn("Checkstyle violations have been fixed. Please review and commit the changes.");
                } else {
                    getLogger().warn("Checkstyle violations have been found.");
                }
            } else {
                if (getAction().compareTo(RewriteAction.FIX_SOURCE) >= 0) {
                    throw new GradleException("Checkstyle violations have been fixed. Please review and commit the changes.");
                } else {
                    throw new GradleException("Checkstyle violations have been found.");
                }
            }
        }
    }

    /**
     * The sources for incremental change detection.
     *
     * @since 6.0
     */
    @Incubating
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    protected FileCollection getStableSources() {
        return stableSources;
    }

    /**
     * Whether or not this task will ignore failures and continue running the build.
     *
     * @return true if failures should be ignored
     */
    @Override
    public boolean getIgnoreFailures() {
        return ignoreFailures;
    }

    /**
     * Whether this task will ignore failures and continue running the build.
     *
     * @return true if failures should be ignored
     */
    @Internal
    public boolean isIgnoreFailures() {
        return ignoreFailures;
    }

    /**
     * Whether this task will ignore failures and continue running the build.
     */
    @Override
    public void setIgnoreFailures(boolean ignoreFailures) {
        this.ignoreFailures = ignoreFailures;
    }

    /**
     * Whether rule violations are to be displayed on the console.
     *
     * @return true if violations should be displayed on console
     */
    @Console
    public boolean isShowViolations() {
        return showViolations;
    }

    /**
     * Whether rule violations are to be displayed on the console.
     */
    public void setShowViolations(boolean showViolations) {
        this.showViolations = showViolations;
    }

    @Optional
    @Input
    public RewriteAction getAction() {
        return action;
    }

    public void setAction(RewriteAction action) {
        this.action = action;
    }

    @Optional
    @Input
    public Set<String> getExcludeChecks() {
        return excludeChecks;
    }

    public void setExcludeChecks(Set<String> excludeChecks) {
        this.excludeChecks = excludeChecks;
    }

    public TextResource getConfig() {
        return config;
    }

    /**
     * The Checkstyle configuration to use. If not set, the task falls back on
     * {@link CheckstyleExtension#getConfig()}.
     */
    @Optional
    @Input
    public void setConfig(TextResource config) {
        this.config = config;
    }

    /**
     * The properties available for use in the configuration file. These are substituted into the configuration file.
     */
    @Nullable
    @Optional
    @Input
    public Map<String, Object> getConfigProperties() {
        return configProperties;
    }

    /**
     * The properties available for use in the configuration file. These are substituted into the configuration file.
     */
    public void setConfigProperties(@Nullable Map<String, Object> configProperties) {
        this.configProperties = configProperties;
    }
}

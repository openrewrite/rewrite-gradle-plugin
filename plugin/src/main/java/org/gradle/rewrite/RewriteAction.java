package org.gradle.rewrite;

public enum RewriteAction {
    WARN_ONLY,
    FIX_SOURCE,
    COMMIT,
    REBASE_FIXUP;
}

/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.util;

import java.io.IOException;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.manager.IGitblit;
import com.gitblit.models.RefModel;
import com.gitblit.models.RepositoryModel;
import com.gitblit.utils.JGitUtils;

/**
 * The name of a repository's default branch: the branch HEAD points at, or,
 * when that branch does not exist, the most recently updated branch. That is
 * the branch Gitblit indexes as "default", and the one /file and /find read
 * without a revision (JGitUtils.getDefaultBranch).
 *
 * RepositoryModel.HEAD is not it: it names HEAD's target whether that exists
 * or not, and a mirror whose upstream renamed master to main keeps a HEAD
 * naming refs/heads/master. Gitblit never drops the index documents of a
 * deleted branch, so a search filtered on that name finds only what the gone
 * branch held.
 */
public final class DefaultBranch {

    private static final Logger log = LoggerFactory.getLogger(DefaultBranch.class);

    private DefaultBranch() {
    }

    /**
     * The default branch's full ref name, e.g. refs/heads/main. When the
     * repository cannot be opened or read, RepositoryModel.HEAD; null when
     * the repository has no branches.
     */
    public static String of(IGitblit gitblit, RepositoryModel model) {
        Repository repository = gitblit.getRepository(model.name);
        if (repository == null) {
            return model.HEAD;
        }
        try {
            return of(repository);
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot resolve the default branch of {}, using HEAD: {}", model.name, e.toString());
            return model.HEAD;
        } finally {
            repository.close();
        }
    }

    public static String of(Repository repository) throws IOException {
        Ref head = repository.exactRef(Constants.HEAD);
        if (head != null && head.isSymbolic() && head.getObjectId() != null) {
            return head.getLeaf().getName();
        }

        // As JGitUtils.getDefaultBranch picks it: the first branch of the
        // latest commit date
        RefModel latest = null;
        for (RefModel branch : JGitUtils.getLocalBranches(repository, true, -1)) {
            if (latest == null || branch.getDate().after(latest.getDate())) {
                latest = branch;
            }
        }
        return latest != null ? latest.getName() : null;
    }
}

package com.teggr.codeagent.agent;

/** A workspace populated from a Git repository. */
public record GitRepositoryWorkspace(String repositoryUrl) implements WorkspaceSpec {

    public GitRepositoryWorkspace {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw new IllegalArgumentException("repositoryUrl must not be blank");
        }
    }
}
package com.teggr.codeagent.agent;

/** A workspace with no repository checked out; the agent starts in an empty working directory. */
public record LocalWorkspace() implements WorkspaceSpec {
}

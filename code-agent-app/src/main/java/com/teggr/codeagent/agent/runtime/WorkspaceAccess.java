package com.teggr.codeagent.agent.runtime;

/** Optional user-facing access details supplied by an agent runtime adapter. */
public record WorkspaceAccess(String uri, String cliCommand) {
}
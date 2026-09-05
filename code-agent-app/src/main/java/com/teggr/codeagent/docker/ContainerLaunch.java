package com.teggr.codeagent.docker;

public record ContainerLaunch(
        String containerId,
        int hostPort,
        String devContainerUri,
        String devContainerCliCommand) {

    public ContainerLaunch(String containerId, int hostPort) {
        this(containerId, hostPort,
                buildDevContainerUri(containerId, "/workspace"),
                buildDevContainerCliCommand(containerId, "/workspace"));
    }

    private static String shortContainerId(String containerId) {
        if (containerId == null) {
            return "";
        }
        return containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
    }

    private static String buildDevContainerUri(String containerId, String workspacePath) {
        return "vscode://vscode-remote/attached-container+" + shortContainerId(containerId) + workspacePath;
    }

    private static String buildDevContainerCliCommand(String containerId, String workspacePath) {
        return "code --folder-uri vscode-remote://attached-container+" + shortContainerId(containerId) + workspacePath;
    }
}

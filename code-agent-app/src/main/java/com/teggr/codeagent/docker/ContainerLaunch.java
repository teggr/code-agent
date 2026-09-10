package com.teggr.codeagent.docker;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public record ContainerLaunch(
        String containerId,
        int hostPort,
        String workspacePath,
        String devContainerUri,
        String devContainerCliCommand) {

    public ContainerLaunch(String containerId, int hostPort) {
        this(containerId, hostPort, "/workspace");
    }

    /** workspacePath should be the cloned repository's directory, e.g. "/workspace/my-repo". */
    public ContainerLaunch(String containerId, int hostPort, String workspacePath) {
        this(containerId, hostPort, workspacePath,
                buildDevContainerUri(containerId, workspacePath),
                buildDevContainerCliCommand(containerId, workspacePath));
    }

    private static String shortContainerId(String containerId) {
        if (containerId == null) {
            return "";
        }
        return containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
    }

    private static String buildDevContainerUri(String containerId, String workspacePath) {
        return "vscode://vscode-remote/attached-container+" + hexEncodedContainerId(containerId) + workspacePath
                + "?windowId=_blank";
    }

    private static String buildDevContainerCliCommand(String containerId, String workspacePath) {
        return "code --new-window --folder-uri vscode-remote://attached-container+" + hexEncodedContainerId(containerId)
                + workspacePath;
    }

    private static String hexEncodedContainerId(String containerId) {
        return HexFormat.of().formatHex(shortContainerId(containerId).getBytes(StandardCharsets.UTF_8));
    }
}

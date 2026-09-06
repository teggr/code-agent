package com.teggr.codeagent.docker;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

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

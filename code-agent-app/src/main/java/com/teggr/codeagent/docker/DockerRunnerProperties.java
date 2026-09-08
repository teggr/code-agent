package com.teggr.codeagent.docker;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codeagent.runner")
public class DockerRunnerProperties {

    private String image = "teggr/code-agent-runner:0.1.0-SNAPSHOT";
    private String gitToken = "";
    private String copilotToken = "";
    private boolean pruneOrphansOnStartup = true;
    private String dockerSocketPath = "";

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getGitToken() {
        return gitToken;
    }

    public void setGitToken(String gitToken) {
        this.gitToken = gitToken;
    }

    public String getCopilotToken() {
        return copilotToken;
    }

    public void setCopilotToken(String copilotToken) {
        this.copilotToken = copilotToken;
    }

    public boolean isPruneOrphansOnStartup() {
        return pruneOrphansOnStartup;
    }

    public void setPruneOrphansOnStartup(boolean pruneOrphansOnStartup) {
        this.pruneOrphansOnStartup = pruneOrphansOnStartup;
    }

    public String getDockerSocketPath() {
        return dockerSocketPath;
    }

    public void setDockerSocketPath(String dockerSocketPath) {
        this.dockerSocketPath = dockerSocketPath;
    }

}

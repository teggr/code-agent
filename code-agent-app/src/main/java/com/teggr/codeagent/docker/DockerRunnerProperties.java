package com.teggr.codeagent.docker;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codeagent.runner")
public class DockerRunnerProperties {

    private String image = "teggr/code-agent-runner:0.1.0-SNAPSHOT";
    private String gitRepoUrl = "https://github.com/teggr/j2html-toolkit";
    private String ghToken = "";

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getGitRepoUrl() {
        return gitRepoUrl;
    }

    public void setGitRepoUrl(String gitRepoUrl) {
        this.gitRepoUrl = gitRepoUrl;
    }

    public String getGhToken() {
        return ghToken;
    }

    public void setGhToken(String ghToken) {
        this.ghToken = ghToken;
    }

}

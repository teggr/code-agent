package com.teggr.codeagent.docker;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codeagent.runner")
public class DockerRunnerProperties {

    private String image = "teggr/code-agent-runner:0.1.0-SNAPSHOT";
    private String ghToken = "";
    private boolean pruneOrphansOnStartup = true;

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getGhToken() {
        return ghToken;
    }

    public void setGhToken(String ghToken) {
        this.ghToken = ghToken;
    }

    public boolean isPruneOrphansOnStartup() {
        return pruneOrphansOnStartup;
    }

    public void setPruneOrphansOnStartup(boolean pruneOrphansOnStartup) {
        this.pruneOrphansOnStartup = pruneOrphansOnStartup;
    }

}

package com.teggr.codeagent.agent.runtime.docker;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codeagent.agent.runtime.docker")
public class DockerAgentRuntimeProperties {

    private String image = "teggr/code-agent-runner:0.1.0-SNAPSHOT";
    private String socketPath = "";

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getSocketPath() {
        return socketPath;
    }

    public void setSocketPath(String socketPath) {
        this.socketPath = socketPath;
    }

}

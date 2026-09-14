package com.teggr.codeagent.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codeagent.agent")
public class AgentProperties {

    private boolean restoreExistingOnStartup = true;

    public boolean isRestoreExistingOnStartup() {
        return restoreExistingOnStartup;
    }

    public void setRestoreExistingOnStartup(boolean restoreExistingOnStartup) {
        this.restoreExistingOnStartup = restoreExistingOnStartup;
    }
}
package com.teggr.codeagent.schedule;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codeagent.schedule")
public class ScheduleProperties {

    private String dataDir = System.getProperty("user.home") + "/.code-agent/data";
    private Duration idleTimeout = Duration.ofMinutes(5);

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }
}

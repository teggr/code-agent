package com.teggr.codeagent;

import java.awt.Desktop;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

import com.teggr.codeagent.docker.DockerRunnerProperties;
import com.teggr.codeagent.runner.RunnerManager;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CodeAgentApplication {

    private static final Logger log = LoggerFactory.getLogger(CodeAgentApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(CodeAgentApplication.class, args);
    }

    @Bean
    public ApplicationListener<ApplicationReadyEvent> desktopLauncher() {
        return event -> {
            try {
                if (Desktop.isDesktopSupported() && Boolean.getBoolean("codeagent.desktop")) {
                    Desktop.getDesktop().browse(new URI("http://127.0.0.1:8080/"));
                }
            } catch (Exception e) {
                System.err.println("Unable to open desktop browser automatically: " + e.getMessage());
            }
        };
    }

    @Bean
    public ApplicationListener<ApplicationReadyEvent> existingRunnerAdoption(
            RunnerManager runnerManager, DockerRunnerProperties properties) {
        return event -> {
            if (!properties.isAdoptExistingOnStartup()) {
                return;
            }
            try {
                runnerManager.adoptExisting();
            } catch (Exception e) {
                log.warn("Unable to adopt runner containers left by a previous run", e);
            }
        };
    }

}

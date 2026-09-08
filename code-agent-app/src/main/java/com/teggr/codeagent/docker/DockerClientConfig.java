package com.teggr.codeagent.docker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;

@Configuration
public class DockerClientConfig {

    private static final Logger log = LoggerFactory.getLogger(DockerClientConfig.class);

    @Bean
    DockerClient dockerClient() {
        var dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        log.info("Using Docker endpoint {}", dockerConfig.getDockerHost());
        var dockerHttpClient = new ZerodepDockerHttpClient.Builder()
            .dockerHost(dockerConfig.getDockerHost())
            .sslConfig(dockerConfig.getSSLConfig())
            .build();
        return DockerClientImpl.getInstance(dockerConfig, dockerHttpClient);
    }

}

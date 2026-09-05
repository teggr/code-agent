package com.teggr.codeagent.docker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

@Configuration
public class DockerClientConfig {

    @Bean
    DockerClient dockerClient() {
        var dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var dockerHttpClient = new ApacheDockerHttpClient.Builder()
            .dockerHost(dockerConfig.getDockerHost())
            .sslConfig(dockerConfig.getSSLConfig())
            .build();
        return DockerClientImpl.getInstance(dockerConfig, dockerHttpClient);
    }

}

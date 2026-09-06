package com.teggr.codeagent.runner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.teggr.codeagent.agent.AgentHarness;
import com.teggr.codeagent.agent.AgentHarnessFactory;
import com.teggr.codeagent.docker.ContainerLaunch;
import com.teggr.codeagent.docker.DockerRunnerService;

/** Tracks the set of active runners, allowing multiple runners per repository. */
@Service
public class RunnerManager {

    private final DockerRunnerService dockerRunnerService;
    private final AgentHarnessFactory agentHarnessFactory;
    private final Map<String, Runner> activeRunners = new ConcurrentHashMap<>();

    public RunnerManager(DockerRunnerService dockerRunnerService, AgentHarnessFactory agentHarnessFactory) {
        this.dockerRunnerService = dockerRunnerService;
        this.agentHarnessFactory = agentHarnessFactory;
    }

    public Runner start(String repoUrl) throws Exception {
        ContainerLaunch containerLaunch = dockerRunnerService.launch(repoUrl);

        AgentHarness harness;
        try {
            harness = agentHarnessFactory.connect(containerLaunch.hostPort());
        } catch (Exception e) {
            dockerRunnerService.stop(containerLaunch.containerId());
            throw e;
        }

        Runner runner = new Runner(UUID.randomUUID().toString(), repoUrl, containerLaunch, harness);
        activeRunners.put(runner.id(), runner);
        return runner;
    }

    public void stop(String runnerId) {
        Runner runner = activeRunners.remove(runnerId);
        if (runner == null) {
            throw new IllegalArgumentException("No active runner with id " + runnerId);
        }
        try {
            runner.harness().close();
        } catch (Exception e) {
            System.err.println("Error closing agent harness for runner " + runnerId + ": " + e.getMessage());
        } finally {
            dockerRunnerService.stop(runner.containerLaunch().containerId());
        }
    }

    public void stopAll() {
        List<String> runnerIds = new ArrayList<>(activeRunners.keySet());
        for (String runnerId : runnerIds) {
            try {
                stop(runnerId);
            } catch (Exception e) {
                System.err.println("Error stopping runner " + runnerId + ": " + e.getMessage());
            }
        }
    }

    public Runner get(String runnerId) {
        return activeRunners.get(runnerId);
    }

    public Collection<Runner> list() {
        return List.copyOf(activeRunners.values());
    }

}

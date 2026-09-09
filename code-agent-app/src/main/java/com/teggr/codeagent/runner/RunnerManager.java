package com.teggr.codeagent.runner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.teggr.codeagent.agent.AgentHarness;
import com.teggr.codeagent.agent.AgentHarnessFactory;
import com.teggr.codeagent.agent.AgentSession;
import com.teggr.codeagent.docker.ContainerLaunch;
import com.teggr.codeagent.docker.DockerRunnerService;

/** Tracks the set of active runners, allowing multiple runners per repository. */
@Service
public class RunnerManager {

    private final DockerRunnerService dockerRunnerService;
    private final AgentHarnessFactory agentHarnessFactory;
    private final RunnerEventPublisher eventPublisher;
    private final Map<String, RunnerSession> activeRunners = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public RunnerManager(DockerRunnerService dockerRunnerService, AgentHarnessFactory agentHarnessFactory,
            RunnerEventPublisher eventPublisher) {
        this.dockerRunnerService = dockerRunnerService;
        this.agentHarnessFactory = agentHarnessFactory;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    void init() {
        eventPublisher.setDashboardSessions(() -> List.copyOf(activeRunners.values()));
    }

    private void wireEvents(RunnerSession session) {
        session.setListener(new RunnerSessionListener() {
            @Override
            public void onMessage(RunnerSession s, ChatMessage message) {
                eventPublisher.publishMessage(s, message);
            }

            @Override
            public void onStatusChange(RunnerSession s, RunnerStatus status) {
                eventPublisher.publishStatus(s, status);
            }

            @Override
            public void onQuestionChange(RunnerSession s, com.teggr.codeagent.agent.Question question) {
                eventPublisher.publishQuestion(s, question);
            }
        });
    }

    public SseEmitter subscribe(String runnerId) {
        return eventPublisher.subscribe(runnerId);
    }

    public SseEmitter subscribeDashboard() {
        return eventPublisher.subscribeDashboard();
    }

    public Runner start(String repoUrl) throws Exception {
        ContainerLaunch containerLaunch = dockerRunnerService.launch(repoUrl);

        AgentHarness harness;
        try {
            harness = agentHarnessFactory.connect(containerLaunch.hostPort(),
                    () -> dockerRunnerService.verifyRunning(containerLaunch.containerId()));
        } catch (Exception e) {
            dockerRunnerService.stop(containerLaunch.containerId());
            throw e;
        }

        Runner runner = new Runner(UUID.randomUUID().toString(), repoUrl, containerLaunch, harness);
        RunnerSession session = new RunnerSession(runner);
        wireEvents(session);
        session.attachAgent(harness.createSession());
        session.setStatus(RunnerStatus.IDLE);
        activeRunners.put(runner.id(), session);
        eventPublisher.publishRunnerList();
        return runner;
    }

    public RunnerSession startAsync(String repoUrl, String prompt) {
        String runnerId = UUID.randomUUID().toString();
        Runner placeholder = new Runner(runnerId, repoUrl, new ContainerLaunch("pending", 0), null);
        RunnerSession session = new RunnerSession(placeholder);
        wireEvents(session);
        session.addMessage("user", prompt);
        session.setStatus(RunnerStatus.STARTING);
        activeRunners.put(runnerId, session);
        eventPublisher.publishRunnerList();

        executor.submit(() -> {
            try {
                System.out.println("[runner " + runnerId + "] Launching container for " + repoUrl);
                ContainerLaunch containerLaunch = dockerRunnerService.launch(repoUrl);
                System.out.println("[runner " + runnerId + "] Container " + containerLaunch.containerId()
                        + " on host port " + containerLaunch.hostPort() + "; connecting agent");
                AgentHarness harness = agentHarnessFactory.connect(containerLaunch.hostPort(),
                        () -> dockerRunnerService.verifyRunning(containerLaunch.containerId()));
                Runner startedRunner = new Runner(runnerId, repoUrl, containerLaunch, harness);
                session.setRunner(startedRunner);
                eventPublisher.publishVscodeLink(session);
                session.attachAgent(harness.createSession());
                System.out.println("[runner " + runnerId + "] Session created; sending prompt");
                session.setStatus(RunnerStatus.BUSY);
                session.agentSession().sendPrompt(prompt);
                System.out.println("[runner " + runnerId + "] Prompt acknowledged by server");
            } catch (Exception e) {
                System.err.println("[runner " + runnerId + "] Failed: " + e.getMessage());
                e.printStackTrace();
                session.setStatus(RunnerStatus.FAILED);
                session.addMessage("assistant", "Error starting runner: " + e.getMessage());
            }
        });

        return session;
    }

    public RunnerSession start(String repoUrl, String prompt) throws Exception {
        RunnerSession session = startAsync(repoUrl, prompt);
        while (session.status() == RunnerStatus.STARTING) {
            Thread.sleep(25);
        }
        return session;
    }

    public void stop(String runnerId) {
        RunnerSession session = activeRunners.remove(runnerId);
        if (session == null) {
            throw new IllegalArgumentException("No active runner with id " + runnerId);
        }
        Runner runner = session.runner();
        try {
            AgentSession agentSession = session.agentSession();
            if (agentSession != null) {
                // AgentSession is a simple wrapper over the Copilot session; this is best-effort cleanup.
            }
            runner.harness().close();
        } catch (Exception e) {
            System.err.println("Error closing agent harness for runner " + runnerId + ": " + e.getMessage());
        } finally {
            dockerRunnerService.stop(runner.containerLaunch().containerId());
            session.setStatus(RunnerStatus.STOPPED);
        }
        eventPublisher.publishRunnerList();
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

    @PreDestroy
    public void shutdown() {
        stopAll();
        eventPublisher.closeAll();
        executor.shutdownNow();
    }

    public RunnerSession getSession(String runnerId) {
        return activeRunners.get(runnerId);
    }

    public Runner get(String runnerId) {
        RunnerSession session = activeRunners.get(runnerId);
        return session == null ? null : session.runner();
    }

    public Collection<RunnerSession> list() {
        return List.copyOf(activeRunners.values());
    }

    public ExecutorService executor() {
        return executor;
    }

}

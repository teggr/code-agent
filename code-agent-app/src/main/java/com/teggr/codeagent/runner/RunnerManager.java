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
import com.teggr.codeagent.docker.ContainerLaunch;
import com.teggr.codeagent.docker.DockerRunnerService;
import com.teggr.codeagent.docker.ManagedContainer;

/** Tracks the set of active runners, allowing multiple runners per repository. */
@Service
public class RunnerManager {

    private final DockerRunnerService dockerRunnerService;
    private final AgentHarnessFactory agentHarnessFactory;
    private final RunnerEventPublisher eventPublisher;
    private final Map<String, RunnerSession> activeRunners = new ConcurrentHashMap<>();
    private final Map<String, Map<String, RunnerSession>> sessionsByRunner = new ConcurrentHashMap<>();
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

    private void registerSession(RunnerSession session) {
        activeRunners.putIfAbsent(session.runner().id(), session);
        sessionsByRunner.computeIfAbsent(session.runner().id(), ignored -> new ConcurrentHashMap<>())
                .put(session.id(), session);
    }

    /** Creates a second independent conversation on an existing runner container. */
    public RunnerSession createSession(String runnerId) throws Exception {
        RunnerSession owner = requireSession(runnerId);
        AgentHarness harness = owner.runner().harness();
        if (harness == null) {
            throw new IllegalStateException("Runner " + runnerId + " has no connected agent");
        }
        RunnerSession session = new RunnerSession(new Runner(runnerId, owner.runner().repoUrl(),
                owner.runner().containerLaunch(), harness));
        wireEvents(session);
        session.attachAgent(harness.createSession(session.id()));
        session.setStatus(RunnerStatus.IDLE);
        sessionsByRunner.computeIfAbsent(runnerId, ignored -> new ConcurrentHashMap<>())
                .put(session.id(), session);
        eventPublisher.publishRunnerList();
        return session;
    }

    public RunnerSession getSession(String runnerId, String sessionId) {
        Map<String, RunnerSession> sessions = sessionsByRunner.get(runnerId);
        return sessions == null ? null : sessions.get(sessionId);
    }

    public Collection<RunnerSession> sessions(String runnerId) {
        Map<String, RunnerSession> sessions = sessionsByRunner.get(runnerId);
        return sessions == null ? List.of() : List.copyOf(sessions.values());
    }

    /** Removes one conversation while leaving the shared runner available to its siblings. */
    public void removeSession(String runnerId, String sessionId) {
        RunnerSession session = getSession(runnerId, sessionId);
        if (session == null) {
            throw new IllegalArgumentException("No session " + sessionId + " on runner " + runnerId);
        }
        Map<String, RunnerSession> sessions = sessionsByRunner.get(runnerId);
        if (sessions.size() == 1) {
            remove(runnerId);
            return;
        }
        sessions.remove(sessionId);
        session.detachAgent();
        if (activeRunners.get(runnerId) == session) {
            activeRunners.put(runnerId, sessions.values().iterator().next());
        }
        eventPublisher.publishRunnerList();
    }

    public SseEmitter subscribe(String runnerId) {
        return eventPublisher.subscribe(runnerId);
    }

    public SseEmitter subscribeSession(String sessionId) {
        return eventPublisher.subscribeSession(sessionId);
    }

    public SseEmitter subscribeDashboard() {
        return eventPublisher.subscribeDashboard();
    }

    public Runner start(String repoUrl) throws Exception {
        String runnerId = UUID.randomUUID().toString();
        ContainerLaunch containerLaunch = dockerRunnerService.launch(repoUrl, runnerId);

        AgentHarness harness;
        try {
            harness = agentHarnessFactory.connect(containerLaunch.hostPort(),
                    containerLaunch.workspacePath(),
                    () -> dockerRunnerService.verifyRunning(containerLaunch.containerId()));
        } catch (Exception e) {
            dockerRunnerService.remove(containerLaunch.containerId());
            throw e;
        }

        Runner runner = new Runner(runnerId, repoUrl, containerLaunch, harness);
        RunnerSession session = new RunnerSession(runner);
        wireEvents(session);
        session.attachAgent(harness.createSession(session.id()));
        session.setStatus(RunnerStatus.IDLE);
        registerSession(session);
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
        registerSession(session);
        eventPublisher.publishRunnerList();

        executor.submit(() -> {
            try {
                System.out.println("[runner " + runnerId + "] Launching container for " + repoUrl);
                ContainerLaunch containerLaunch = dockerRunnerService.launch(repoUrl, runnerId);
                System.out.println("[runner " + runnerId + "] Container " + containerLaunch.containerId()
                        + " on host port " + containerLaunch.hostPort() + "; connecting agent");
                AgentHarness harness = agentHarnessFactory.connect(containerLaunch.hostPort(),
                        containerLaunch.workspacePath(),
                        () -> dockerRunnerService.verifyRunning(containerLaunch.containerId()));
                Runner startedRunner = new Runner(runnerId, repoUrl, containerLaunch, harness);
                session.setRunner(startedRunner);
                eventPublisher.publishVscodeLink(session);
                session.attachAgent(harness.createSession(session.id()));
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

    /**
     * Re-registers the runner containers left by the previous application run, restoring any stored
     * Copilot sessions from the runner container when available.
     */
    public void adoptExisting() {
        for (ManagedContainer managed : dockerRunnerService.listManaged()) {
            if (activeRunners.containsKey(managed.runnerId())) {
                continue;
            }
            executor.submit(() -> adoptManaged(managed));
        }
        eventPublisher.publishRunnerList();
    }

    /** Starts a stopped runner's container again and connects a new agent session to it. */
    public void restart(String runnerId) {
        RunnerSession session = requireSession(runnerId);
        if (session.agentSession() != null) {
            return;
        }
        session.setStatus(RunnerStatus.RECONNECTING);
        eventPublisher.publishRunnerList();
        executor.submit(() -> reconnect(session, session.runner().containerLaunch(), true));
    }

    private void reconnect(RunnerSession session, ContainerLaunch launch, boolean startContainer) {
        String runnerId = session.runner().id();
        String repoUrl = session.runner().repoUrl();
        try {
            ContainerLaunch connected = startContainer
                    ? dockerRunnerService.restart(launch.containerId(), launch.workspacePath())
                    : launch;
            System.out.println("[runner " + runnerId + "] Connecting to container "
                    + connected.containerId() + " on host port " + connected.hostPort());
            AgentHarness harness = agentHarnessFactory.connect(connected.hostPort(),
                    connected.workspacePath(),
                    () -> dockerRunnerService.verifyRunning(connected.containerId()));
            Runner connectedRunner = new Runner(runnerId, repoUrl, connected, harness);
            for (RunnerSession child : sessions(runnerId)) {
                child.setRunner(connectedRunner);
                eventPublisher.publishVscodeLink(child);
                reconnectChildSession(harness, child);
                child.setStatus(RunnerStatus.IDLE);
            }
        } catch (Exception e) {
            System.err.println("[runner " + runnerId + "] Reconnect failed: " + e.getMessage());
            session.setStatus(RunnerStatus.FAILED);
            session.addMessage("assistant", "Error reconnecting to runner: " + e.getMessage());
        }
        eventPublisher.publishRunnerList();
    }

    private void adoptManaged(ManagedContainer managed) {
        String runnerId = managed.runnerId();
        String repoUrl = managed.repoUrl();
        try {
            ContainerLaunch connected = managed.running()
                    ? managed.launch()
                    : dockerRunnerService.restart(managed.launch().containerId(), managed.launch().workspacePath());
            AgentHarness harness = agentHarnessFactory.connect(connected.hostPort(), connected.workspacePath(),
                    () -> dockerRunnerService.verifyRunning(connected.containerId()));
            Runner connectedRunner = new Runner(runnerId, repoUrl, connected, harness);
            List<String> sessionIds = harness.listSessionIds();
            if (sessionIds == null || sessionIds.isEmpty()) {
                RunnerSession session = new RunnerSession(connectedRunner);
                wireEvents(session);
                session.attachAgent(harness.createSession(session.id()));
                session.addMessage("system", "Reconnected to an existing runner; no persisted session history was found.");
                session.setStatus(RunnerStatus.IDLE);
                registerSession(session);
            } else {
                for (String sessionId : sessionIds) {
                    RunnerSession session = new RunnerSession(sessionId, connectedRunner);
                    wireEvents(session);
                    session.attachAgent(harness.resumeSession(sessionId));
                    session.setStatus(RunnerStatus.IDLE);
                    registerSession(session);
                }
            }
        } catch (Exception e) {
            System.err.println("[runner " + runnerId + "] Adopt failed: " + e.getMessage());
        }
        eventPublisher.publishRunnerList();
    }

    private void reconnectChildSession(AgentHarness harness, RunnerSession child) throws Exception {
        try {
            child.attachAgent(harness.resumeSession(child.id()));
        } catch (Exception e) {
            System.err.println("[runner " + child.runner().id() + "] Unable to resume session " + child.id()
                    + "; creating a new one: " + e.getMessage());
            child.attachAgent(harness.createSession(child.id()));
            child.addMessage("system",
                    "Reconnected to an existing runner, but the earlier session could not be resumed.");
        }
    }

    /** Stops the runner's container but keeps it, so the runner can be started again later. */
    public void stop(String runnerId) {
        RunnerSession session = requireSession(runnerId);
        closeAgent(session);
        for (RunnerSession child : sessions(runnerId)) {
            child.detachAgent();
        }
        dockerRunnerService.stop(session.runner().containerLaunch().containerId());
        for (RunnerSession child : sessions(runnerId)) {
            child.setStatus(RunnerStatus.STOPPED);
        }
        eventPublisher.publishRunnerList();
    }

    /** Deletes the runner's container and forgets the runner entirely. */
    public void remove(String runnerId) {
        RunnerSession session = activeRunners.remove(runnerId);
        if (session == null) {
            throw new IllegalArgumentException("No active runner with id " + runnerId);
        }
        List<RunnerSession> children = List.copyOf(sessions(runnerId));
        sessionsByRunner.remove(runnerId);
        closeAgent(session);
        dockerRunnerService.remove(session.runner().containerLaunch().containerId());
        for (RunnerSession child : children) {
            child.setStatus(RunnerStatus.REMOVED);
        }
        eventPublisher.publishRunnerList();
    }

    private RunnerSession requireSession(String runnerId) {
        RunnerSession session = activeRunners.get(runnerId);
        if (session == null) {
            throw new IllegalArgumentException("No active runner with id " + runnerId);
        }
        return session;
    }

    private void closeAgent(RunnerSession session) {
        AgentHarness harness = session.runner().harness();
        session.detachAgent();
        if (harness == null) {
            return;
        }
        try {
            harness.close();
        } catch (Exception e) {
            System.err.println("Error closing agent harness for runner " + session.runner().id()
                    + ": " + e.getMessage());
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

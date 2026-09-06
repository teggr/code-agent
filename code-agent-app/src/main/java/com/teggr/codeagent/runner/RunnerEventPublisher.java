package com.teggr.codeagent.runner;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans runner session events out to browser SSE streams as rendered HTML fragments
 * (consumed by htmx's SSE extension). A dead emitter is dropped silently so one
 * disconnected tab never affects the others.
 */
@Component
public class RunnerEventPublisher {

    private static final long EMITTER_TIMEOUT_MS = 30L * 60L * 1000L;

    private final FragmentRenderer fragments;
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> perRunner = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> dashboardSubscribers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SseEmitter> allEmitters = new CopyOnWriteArrayList<>();
    private volatile Supplier<List<RunnerSession>> dashboardSessions;

    public RunnerEventPublisher(FragmentRenderer fragments) {
        this.fragments = fragments;
    }

    /** Called once by RunnerManager so the publisher can re-render the dashboard list. */
    public void setDashboardSessions(Supplier<List<RunnerSession>> sessions) {
        this.dashboardSessions = sessions;
    }

    /** Subscribes a browser tab to one runner's message/status events. */
    public SseEmitter subscribe(String runnerId) {
        return register(perRunner.computeIfAbsent(runnerId, id -> new CopyOnWriteArrayList<>()));
    }

    /** Subscribes a browser tab to dashboard runner-list updates. */
    public SseEmitter subscribeDashboard() {
        return register(dashboardSubscribers);
    }

    public void publishMessage(RunnerSession session, ChatMessage message) {
        sendAll(perRunner.get(session.runner().id()), "message", fragments.message(message));
        publishRunnerList();
    }

    public void publishStatus(RunnerSession session, RunnerStatus status) {
        sendAll(perRunner.get(session.runner().id()), "status", fragments.statusBadge(status));
        publishRunnerList();
    }

    public void publishRunnerList() {
        Supplier<List<RunnerSession>> supplier = dashboardSessions;
        if (supplier != null) {
            sendAll(dashboardSubscribers, "runnerList", fragments.runnerList(supplier.get()));
        }
    }

    /** Completes all open emitters on shutdown so browser tabs stop retrying. */
    public void closeAll() {
        for (SseEmitter emitter : allEmitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // already gone
            }
        }
    }

    private SseEmitter register(CopyOnWriteArrayList<SseEmitter> registry) {
        SseEmitter emitter = newEmitter();
        registry.add(emitter);
        allEmitters.add(emitter);
        Runnable drop = () -> {
            registry.remove(emitter);
            allEmitters.remove(emitter);
        };
        emitter.onCompletion(drop);
        emitter.onTimeout(drop);
        emitter.onError(e -> drop.run());
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            drop.run();
        }
        return emitter;
    }

    /** Factory seam so tests can substitute a recording emitter. */
    protected SseEmitter newEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MS);
    }

    private void sendAll(CopyOnWriteArrayList<SseEmitter> registry, String event, String html) {
        if (registry == null || registry.isEmpty()) {
            return;
        }
        // SSE data fields are line-based: emit one data: line per HTML line.
        // The browser concatenates them back with '\n', preserving pre-wrap content.
        String[] lines = html.split("\r\n|\r|\n");
        for (SseEmitter emitter : registry) {
            try {
                SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event().name(event);
                for (String line : lines) {
                    eventBuilder.data(line);
                }
                emitter.send(eventBuilder);
            } catch (Exception e) {
                registry.remove(emitter);
                allEmitters.remove(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // already gone
                }
            }
        }
    }

}

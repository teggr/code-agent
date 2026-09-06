package com.teggr.codeagent.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.teggr.codeagent.docker.ContainerLaunch;

/** Uses a real auto-configured Thymeleaf TemplateEngine so fragments render exactly as in production. */
@SpringBootTest(classes = RunnerEventPublisherTest.TestConfig.class)
class RunnerEventPublisherTest {

    private static final Map<SseEmitter, List<String>> SENT = new ConcurrentHashMap<>();

    @Configuration
    @Import(org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration.class)
    static class TestConfig {
        @Bean
        FragmentRenderer fragmentRenderer(org.thymeleaf.TemplateEngine templateEngine) {
            return new FragmentRenderer(templateEngine);
        }

        @Bean
        RunnerEventPublisher runnerEventPublisher(FragmentRenderer fragments) {
            return new RunnerEventPublisher(fragments) {
                @Override
                protected SseEmitter newEmitter() {
                    return new RecordingSseEmitter();
                }
            };
        }
    }

    @Autowired
    private RunnerEventPublisher publisher;

    @Test
    void messageEventPushesRenderedFragmentToRunnerSubscribers() {
        SseEmitter emitter = publisher.subscribe("runner-1");
        RunnerSession session = session("runner-1", "some-repo");

        publisher.publishMessage(session, new ChatMessage("m1", "assistant", "hello world", Instant.now()));

        assertThat(sent(emitter)).anyMatch(e -> e.contains("hello world") && e.contains("assistant"));
    }

    @Test
    void statusEventPushesRenderedBadgeToRunnerSubscribers() {
        SseEmitter emitter = publisher.subscribe("runner-1");

        publisher.publishStatus(session("runner-1", "repo"), RunnerStatus.BUSY);

        assertThat(sent(emitter)).anyMatch(e -> e.contains("BUSY"));
    }

    @Test
    void sessionListenerForwardsEventsToPublisher() {
        SseEmitter emitter = publisher.subscribe("runner-1");
        RunnerSession session = session("runner-1", "repo");
        session.setListener(new RunnerSessionListener() {
            @Override
            public void onMessage(RunnerSession s, ChatMessage message) {
                publisher.publishMessage(s, message);
            }

            @Override
            public void onStatusChange(RunnerSession s, RunnerStatus status) {
                publisher.publishStatus(s, status);
            }
        });

        session.addMessage("assistant", "from listener");
        session.setStatus(RunnerStatus.BUSY);

        assertThat(sent(emitter)).anyMatch(e -> e.contains("from listener"));
        assertThat(sent(emitter)).anyMatch(e -> e.contains("BUSY"));
    }

    @Test
    void dashboardSubscribersReceiveRunnerListOnMessage() {
        SseEmitter dashboard = publisher.subscribeDashboard();
        RunnerSession session = session("runner-9", "some-repo");
        publisher.setDashboardSessions(() -> List.of(session));

        publisher.publishMessage(session, new ChatMessage("m1", "assistant", "hi", Instant.now()));

        assertThat(sent(dashboard)).anyMatch(e -> e.contains("runner-9") && e.contains("some-repo"));
    }

    @Test
    void multiLineContentIsSplitIntoSeparateDataLines() {
        SseEmitter emitter = publisher.subscribe("runner-1");
        RunnerSession session = session("runner-1", "repo");

        publisher.publishMessage(session, new ChatMessage("m1", "assistant", "line one\nline two", Instant.now()));

        List<String> events = sent(emitter);
        assertThat(events).anyMatch(e -> e.contains("line one"));
        assertThat(events).anyMatch(e -> e.contains("line two"));
        // No single SSE data entry may contain a raw newline (would corrupt the stream).
        assertThat(events).allSatisfy(e -> assertThat(e).doesNotContain("line one\nline two"));
    }

    @Test
    void completedEmitterIsDroppedWhileOthersStillReceive() {
        SseEmitter dead = publisher.subscribe("runner-1");
        SseEmitter alive = publisher.subscribe("runner-1");
        RunnerSession session = session("runner-1", "repo");

        dead.complete();
        publisher.publishMessage(session, new ChatMessage("m1", "assistant", "still delivered", Instant.now()));

        assertThat(sent(alive)).anyMatch(e -> e.contains("still delivered"));
    }

    private RunnerSession session(String runnerId, String repoUrl) {
        return new RunnerSession(new Runner(runnerId, repoUrl, new ContainerLaunch("c", 0), null));
    }

    private static List<String> sent(SseEmitter emitter) {
        return SENT.getOrDefault(emitter, List.of());
    }

    /** Records every send instead of writing to a response. */
    private static final class RecordingSseEmitter extends SseEmitter {
        RecordingSseEmitter() {
            super(60_000L);
            SENT.put(this, new CopyOnWriteArrayList<>());
        }

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder sb = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType d : builder.build()) {
                sb.append(String.valueOf(d.getData())).append('\n');
            }
            SENT.get(this).add(sb.toString());
        }

        @Override
        public void send(Object object) {
            SENT.get(this).add(String.valueOf(object));
        }

        @Override
        public void send(Object object, MediaType mediaType) {
            SENT.get(this).add(String.valueOf(object));
        }
    }

}

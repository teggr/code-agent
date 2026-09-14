package com.teggr.codeagent.agent;

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

@SpringBootTest(classes = AgentEventPublisherTest.TestConfig.class)
class AgentEventPublisherTest {

    private static final Map<SseEmitter, List<String>> SENT = new ConcurrentHashMap<>();

    @Configuration
    @Import(org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration.class)
    static class TestConfig {
        @Bean
        FragmentRenderer fragmentRenderer(org.thymeleaf.TemplateEngine templateEngine) {
            return new FragmentRenderer(templateEngine);
        }

        @Bean
        AgentEventPublisher agentEventPublisher(FragmentRenderer fragments) {
            return new AgentEventPublisher(fragments) {
                @Override
                protected SseEmitter newEmitter() {
                    return new RecordingSseEmitter();
                }
            };
        }
    }

    @Autowired
    private AgentEventPublisher publisher;

    @Test
    void messageEventReachesAgentAndConversationSubscribers() {
        SseEmitter agentEmitter = publisher.subscribe("agent-1");
        SseEmitter conversationEmitter = publisher.subscribeConversation("conversation-1");
        AgentConversation conversation = conversation();

        publisher.publishMessage(conversation, ChatMessage.create("m1", "assistant", "hello", Instant.now()));

        assertThat(sent(agentEmitter)).anyMatch(event -> event.contains("hello") && event.contains("assistant"));
        assertThat(sent(conversationEmitter)).anyMatch(event -> event.contains("hello") && event.contains("assistant"));
    }

    @Test
    void conversationStatusReachesAgentAndConversationSubscribers() {
        SseEmitter emitter = publisher.subscribe("agent-1");
        AgentConversation conversation = conversation();

        publisher.publishStatus(conversation, AgentConversationStatus.BUSY);

        assertThat(sent(emitter)).anyMatch(event -> event.contains("BUSY"));
    }

    @Test
    void conversationListenerForwardsMessagesAndStatuses() {
        SseEmitter emitter = publisher.subscribe("agent-1");
        AgentConversation conversation = conversation();
        conversation.setListener(listener());

        conversation.addMessage("assistant", "from listener");
        conversation.setStatus(AgentConversationStatus.BUSY);

        assertThat(sent(emitter)).anyMatch(event -> event.contains("from listener"));
        assertThat(sent(emitter)).anyMatch(event -> event.contains("BUSY"));
    }

    @Test
    void blankMessagesAreNeitherStoredNorPublished() {
        SseEmitter emitter = publisher.subscribe("agent-1");
        AgentConversation conversation = conversation();
        conversation.setListener(listener());
        int eventCount = sent(emitter).size();

        conversation.addMessage("assistant", "  \n\t");

        assertThat(conversation.messages()).isEmpty();
        assertThat(sent(emitter)).hasSize(eventCount);
    }

    @Test
    void dashboardUsesAgentListEvent() {
        SseEmitter emitter = publisher.subscribeDashboard();
        Agent agent = conversation().agent();
        publisher.setDashboardAgents(() -> List.of(agent));

        publisher.publishAgentList();

        assertThat(sent(emitter)).anyMatch(event -> event.contains("agent-1") && event.contains("repository"));
    }

    @Test
    void messageRefreshesDashboardAgentList() {
        SseEmitter dashboard = publisher.subscribeDashboard();
        AgentConversation conversation = conversation();
        publisher.setDashboardAgents(() -> List.of(conversation.agent()));

        publisher.publishMessage(conversation, ChatMessage.create("m1", "assistant", "hello", Instant.now()));

        assertThat(sent(dashboard)).anyMatch(event -> event.contains("agent-1") && event.contains("repository"));
    }

    @Test
    void compactActivityMessagesRenderInlineAndDoNotRefreshDashboardAgentList() {
        SseEmitter agent = publisher.subscribe("agent-1");
        SseEmitter dashboard = publisher.subscribeDashboard();
        AgentConversation conversation = conversation();
        publisher.setDashboardAgents(() -> List.of(conversation.agent()));
        int eventCount = sent(dashboard).size();

        publisher.publishMessage(conversation, ChatMessage.create("m1", "tool", "Running terminal...", Instant.now()));

        assertThat(sent(agent)).anyMatch(event -> event.contains("activity-details")
                && event.contains("Running terminal..."));
        assertThat(sent(dashboard)).hasSize(eventCount);
    }

    @Test
    void usageMessagesUpdateTurnSummaryInsteadOfTimeline() {
        SseEmitter emitter = publisher.subscribe("agent-1");
        AgentConversation conversation = conversation();

        publisher.publishMessage(conversation,
                ChatMessage.create("m1", "system", "Usage: 100/200 tokens", Instant.now(), "SessionUsageInfoEvent"));

        assertThat(sent(emitter)).anyMatch(event -> event.contains("turnSummary")
                && event.contains("Usage: 100/200 tokens"));
        assertThat(sent(emitter)).noneMatch(event -> event.contains("event:message")
                && event.contains("Usage: 100/200 tokens"));
    }

    @Test
    void multilineContentIsSplitIntoSeparateSseDataLines() {
        SseEmitter emitter = publisher.subscribe("agent-1");
        AgentConversation conversation = conversation();

        publisher.publishMessage(conversation,
            ChatMessage.create("m1", "assistant", "line one\nline two", Instant.now()));

        assertThat(sent(emitter)).anyMatch(event -> event.contains("line one"));
        assertThat(sent(emitter)).anyMatch(event -> event.contains("line two"));
        assertThat(sent(emitter)).allSatisfy(event -> assertThat(event).doesNotContain("line one\nline two"));
    }

    @Test
    void completedEmitterIsDroppedWhileOtherSubscribersStillReceive() {
        SseEmitter completed = publisher.subscribe("agent-1");
        SseEmitter active = publisher.subscribe("agent-1");

        completed.complete();
        publisher.publishMessage(conversation(),
            ChatMessage.create("m1", "assistant", "still delivered", Instant.now()));

        assertThat(sent(active)).anyMatch(event -> event.contains("still delivered"));
    }

    private AgentConversationListener listener() {
        return new AgentConversationListener() {
            @Override
            public void onMessage(AgentConversation conversation, ChatMessage message) {
                publisher.publishMessage(conversation, message);
            }

            @Override
            public void onStatusChange(AgentConversation conversation, AgentConversationStatus status) {
                publisher.publishStatus(conversation, status);
            }

            @Override
            public void onQuestionChange(AgentConversation conversation,
                    com.teggr.codeagent.harness.Question question) {
                publisher.publishQuestion(conversation, question);
            }
        };
    }

    private AgentConversation conversation() {
        Agent agent = new Agent("agent-1",
                new GitRepositoryWorkspace("https://github.com/teggr/repository"), null, null, AgentStatus.RUNNING);
        return new AgentConversation("conversation-1", agent);
    }

    private static List<String> sent(SseEmitter emitter) {
        return SENT.getOrDefault(emitter, List.of());
    }

    private static final class RecordingSseEmitter extends SseEmitter {
        RecordingSseEmitter() {
            super(60_000L);
            SENT.put(this, new CopyOnWriteArrayList<>());
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            StringBuilder value = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                value.append(item.getData()).append('\n');
            }
            SENT.get(this).add(value.toString());
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
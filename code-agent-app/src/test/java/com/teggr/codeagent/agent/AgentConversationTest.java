package com.teggr.codeagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.teggr.codeagent.harness.HarnessHistoryEntry;
import com.teggr.codeagent.harness.HarnessSession;
import com.teggr.codeagent.harness.Question;

class AgentConversationTest {

    @Test
    void attachingSessionLoadsPersistedHistoryAndIgnoresBlankEntries() throws Exception {
        HarnessSession session = mock(HarnessSession.class);
        when(session.history()).thenReturn(List.of(
            new HarnessHistoryEntry("user", "Inspect the project", "userMessage"),
            new HarnessHistoryEntry("assistant", "  ", "assistantMessage"),
            new HarnessHistoryEntry("assistant", "The build is healthy", "assistantMessage")));
        AgentConversation conversation = conversation();

        conversation.attachHarnessSession(session);

        assertThat(conversation.messages().stream()
            .map(message -> org.assertj.core.groups.Tuple.tuple(message.role(), message.content()))
            .toList()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("user", "Inspect the project"),
                org.assertj.core.groups.Tuple.tuple("assistant", "The build is healthy"));
    }

    @Test
    void existingHistoryIsNotDuplicatedWhenSessionIsReattached() throws Exception {
        HarnessSession first = mock(HarnessSession.class);
        HarnessSession resumed = mock(HarnessSession.class);
        when(first.history()).thenReturn(List.of(new HarnessHistoryEntry("user", "Original prompt", "userMessage")));
        when(resumed.history()).thenReturn(List.of(new HarnessHistoryEntry("user", "Original prompt", "userMessage")));
        AgentConversation conversation = conversation();

        conversation.attachHarnessSession(first);
        conversation.detachHarnessSession();
        conversation.attachHarnessSession(resumed);

        assertThat(conversation.messages().stream().map(message -> message.content()).toList())
            .containsExactly("Original prompt");
        verify(first).history();
    }

    @Test
    void harnessErrorRemainsFailedWhenIdleArrivesLater() throws Exception {
        HarnessSession session = mock(HarnessSession.class);
        ArgumentCaptor<Consumer<String>> error = consumerCaptor();
        ArgumentCaptor<Runnable> idle = ArgumentCaptor.forClass(Runnable.class);
        AgentConversation conversation = conversation();

        conversation.attachHarnessSession(session);
        verify(session).onError(error.capture());
        verify(session).onIdle(idle.capture());
        error.getValue().accept("authentication expired");
        idle.getValue().run();

        assertThat(conversation.status()).isEqualTo(AgentConversationStatus.FAILED);
        assertThat(conversation.messages().stream().map(message -> message.content()).toList())
                .contains("Copilot error: authentication expired");
    }

    @Test
    void answeringPendingQuestionCompletesFutureAndRecordsAnswer() throws Exception {
        HarnessSession session = mock(HarnessSession.class);
        ArgumentCaptor<Function<Question, CompletableFuture<String>>> handler = functionCaptor();
        AgentConversation conversation = conversation();
        conversation.attachHarnessSession(session);
        verify(session).onQuestion(handler.capture());
        Question question = new Question("question-1", "Which environment?", List.of("dev", "prod"));

        CompletableFuture<String> answer = handler.getValue().apply(question);
        conversation.answerQuestion(question.id(), "prod");

        assertThat(answer).isCompletedWithValue("prod");
        assertThat(conversation.pendingQuestion()).isNull();
        assertThat(conversation.messages().stream().map(message -> message.content()).toList())
            .containsExactly("prod");
        }

        @Test
        void classifiesConversationActivityAndUsageMessagesForTimelineRendering() {
        AgentConversation conversation = conversation();

        conversation.addMessage("user", "Run tests");
        conversation.addMessage("tool", "Running terminal...");
        conversation.addMessage("system", "Usage: 100/200 tokens", "SessionUsageInfoEvent");

        assertThat(conversation.messages()).extracting(message -> message.display()).containsExactly(
            ChatMessageDisplay.FULL_MESSAGE,
            ChatMessageDisplay.COMPACT_ACTIVITY,
            ChatMessageDisplay.TURN_SUMMARY);
        assertThat(conversation.timelineMessages()).extracting(message -> message.content())
            .containsExactly("Run tests", "Running terminal...");
        assertThat(conversation.turnSummary()).hasValueSatisfying(message ->
            assertThat(message.content()).isEqualTo("Usage: 100/200 tokens"));
    }

    private static AgentConversation conversation() {
        Agent agent = new Agent("agent-1", new GitRepositoryWorkspace("https://github.com/teggr/repository"),
                null, null, AgentStatus.RUNNING);
        return new AgentConversation("conversation-1", agent);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Consumer<String>> consumerCaptor() {
        return ArgumentCaptor.forClass(Consumer.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Function<Question, CompletableFuture<String>>> functionCaptor() {
        return ArgumentCaptor.forClass(Function.class);
    }
}
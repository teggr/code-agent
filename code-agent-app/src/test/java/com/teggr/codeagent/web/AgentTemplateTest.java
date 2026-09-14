package com.teggr.codeagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.teggr.codeagent.agent.Agent;
import com.teggr.codeagent.agent.AgentConversationStatus;
import com.teggr.codeagent.agent.AgentStatus;
import com.teggr.codeagent.agent.ChatMessage;
import com.teggr.codeagent.agent.GitRepositoryWorkspace;

@SpringBootTest(classes = AgentTemplateTest.TestConfig.class)
class AgentTemplateTest {

    @Configuration
    @Import(org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration.class)
    static class TestConfig {
    }

    @Autowired
    private TemplateEngine templateEngine;

    @Test
    void rendersTimelineMessagesWithCompactActivityAndTurnSummary() {
        Context context = new Context();
        context.setVariable("agentId", "agent-1");
        context.setVariable("conversationId", "conversation-1");
        context.setVariable("conversations", List.of(new Agent("conversation-1",
                new GitRepositoryWorkspace("https://github.com/teggr/repository"), null, null, AgentStatus.RUNNING)));
        context.setVariable("repositoryUrl", "https://github.com/teggr/repository");
        context.setVariable("agentStatus", AgentStatus.RUNNING);
        context.setVariable("conversationStatus", AgentConversationStatus.IDLE);
        context.setVariable("workspaceReady", true);
        context.setVariable("workspaceUri", "vscode://workspace");
        context.setVariable("promptable", true);
        context.setVariable("pendingQuestion", null);
        context.setVariable("turnSummary", ChatMessage.create("m3", "system", "Usage: 100/200 tokens",
                Instant.now(), "SessionUsageInfoEvent"));
        context.setVariable("timelineMessages", List.of(
                ChatMessage.create("m1", "assistant", "Ready", Instant.now()),
                ChatMessage.create("m2", "tool", "Running terminal...", Instant.now())));

        String html = templateEngine.process("agent", context);

        assertThat(html).contains("Ready");
        assertThat(html).contains("activity-details");
        assertThat(html).contains("Running terminal...");
        assertThat(html).contains("Usage: 100/200 tokens");
    }

        @Test
        void rendersUnavailableAgentRecoveryActions() {
                Context context = baseContext(AgentStatus.UNAVAILABLE);

                String html = templateEngine.process("agent", context);

                assertThat(html).contains("UNAVAILABLE");
                assertThat(html).contains("Reconnect");
                assertThat(html).contains("Stop runtime");
                assertThat(html).contains("Delete runtime");
                assertThat(html).contains("name=\"prompt\"");
                assertThat(html).contains("disabled=\"disabled\"");
        }

        private static Context baseContext(AgentStatus agentStatus) {
                Context context = new Context();
                context.setVariable("agentId", "agent-1");
                context.setVariable("conversationId", "conversation-1");
                context.setVariable("conversations", List.of(new Agent("conversation-1",
                                new GitRepositoryWorkspace("https://github.com/teggr/repository"), null, null, agentStatus)));
                context.setVariable("repositoryUrl", "https://github.com/teggr/repository");
                context.setVariable("agentStatus", agentStatus);
                context.setVariable("conversationStatus", AgentConversationStatus.FAILED);
                context.setVariable("workspaceReady", false);
                context.setVariable("workspaceUri", "vscode://workspace");
                context.setVariable("promptable", false);
                context.setVariable("pendingQuestion", null);
                context.setVariable("turnSummary", null);
                context.setVariable("timelineMessages", List.of(ChatMessage.create("m1", "system",
                                "Unable to reconnect to the existing agent runtime: connect failed", Instant.now())));
                return context;
        }
}
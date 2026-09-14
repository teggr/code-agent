package com.teggr.codeagent.agent;

import java.util.Set;

import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Component("agentFragmentRenderer")
public class FragmentRenderer {

    private final TemplateEngine templateEngine;

    public FragmentRenderer(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public String message(ChatMessage message) {
        Context context = new Context();
        context.setVariable("message", message);
        return templateEngine.process("fragments", Set.of("message"), context);
    }

    public String turnSummary(ChatMessage message) {
        Context context = new Context();
        context.setVariable("message", message);
        return templateEngine.process("fragments", Set.of("turnSummary"), context);
    }

    public String conversationStatus(AgentConversationStatus status) {
        Context context = new Context();
        context.setVariable("conversationStatus", status);
        return templateEngine.process("fragments", Set.of("conversationStatusBadge"), context);
    }

    public String pendingQuestion(String agentId, String conversationId,
            com.teggr.codeagent.harness.Question question) {
        Context context = new Context();
        context.setVariable("agentId", agentId);
        context.setVariable("conversationId", conversationId);
        context.setVariable("question", question);
        return templateEngine.process("fragments", Set.of("pendingQuestion"), context);
    }

    public String agentList(Iterable<Agent> agents) {
        Context context = new Context();
        context.setVariable("agents", agents);
        return templateEngine.process("fragments", Set.of("agentList"), context);
    }

    public String vscodeLink(String workspaceUri, boolean ready) {
        Context context = new Context();
        context.setVariable("workspaceUri", workspaceUri);
        context.setVariable("ready", ready);
        return templateEngine.process("fragments", Set.of("vscodeLink"), context);
    }
}
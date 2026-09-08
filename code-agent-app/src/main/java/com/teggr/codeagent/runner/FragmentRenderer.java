package com.teggr.codeagent.runner;

import java.util.Set;

import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/** Renders small Thymeleaf fragments for SSE pushes, reusing the same markup as the full pages. */
@Component
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

    public String statusBadge(RunnerStatus status) {
        Context context = new Context();
        context.setVariable("status", status);
        return templateEngine.process("fragments", Set.of("statusBadge"), context);
    }

    public String runnerList(Iterable<? extends RunnerSession> sessions) {
        Context context = new Context();
        context.setVariable("runners", sessions);
        return templateEngine.process("fragments", Set.of("runnerList"), context);
    }

    public String vscodeLink(String devContainerUri, boolean ready) {
        Context context = new Context();
        context.setVariable("devContainerUri", devContainerUri);
        context.setVariable("ready", ready);
        return templateEngine.process("fragments", Set.of("vscodeLink"), context);
    }

}

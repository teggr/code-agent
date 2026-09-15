package com.teggr.codeagent.schedule;

import java.util.Set;

import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Component
public class ScheduledTaskFragmentRenderer {

    private final TemplateEngine templateEngine;
    private final IdleCompletionWatcher idleCompletionWatcher;

    public ScheduledTaskFragmentRenderer(TemplateEngine templateEngine, IdleCompletionWatcher idleCompletionWatcher) {
        this.templateEngine = templateEngine;
        this.idleCompletionWatcher = idleCompletionWatcher;
    }

    public String scheduledTaskList(Iterable<ScheduledAgentTask> tasks) {
        Context context = new Context();
        context.setVariable("scheduledTasks", tasks);
        context.setVariable("shutdownEstimates", idleCompletionWatcher.shutdownEstimates(tasks));
        return templateEngine.process("fragments", Set.of("scheduledTaskList"), context);
    }
}

package com.teggr.codeagent.schedule;

import java.util.Set;

import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Component
public class ScheduledTaskFragmentRenderer {

    private final TemplateEngine templateEngine;

    public ScheduledTaskFragmentRenderer(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public String scheduledTaskList(Iterable<ScheduledAgentTask> tasks) {
        Context context = new Context();
        context.setVariable("scheduledTasks", tasks);
        return templateEngine.process("fragments", Set.of("scheduledTaskList"), context);
    }
}

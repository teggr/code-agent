package com.teggr.codeagent.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import com.teggr.codeagent.schedule.ScheduledTaskService;

@Controller
public class ScheduledTaskWebController {

    private final ScheduledTaskService scheduledTaskService;

    public ScheduledTaskWebController(ScheduledTaskService scheduledTaskService) {
        this.scheduledTaskService = scheduledTaskService;
    }

    @PostMapping("/scheduled-tasks/{taskId}/pause")
    public ResponseEntity<Void> pause(@PathVariable String taskId) {
        scheduledTaskService.pause(taskId);
        return redirect("/");
    }

    @PostMapping("/scheduled-tasks/{taskId}/resume")
    public ResponseEntity<Void> resume(@PathVariable String taskId) {
        scheduledTaskService.resume(taskId);
        return redirect("/");
    }

    @PostMapping("/scheduled-tasks/{taskId}/delete")
    public ResponseEntity<Void> delete(@PathVariable String taskId) {
        scheduledTaskService.delete(taskId);
        return redirect("/");
    }

    private ResponseEntity<Void> redirect(String path) {
        return ResponseEntity.status(303).location(URI.create(path)).build();
    }
}

package com.teggr.codeagent.web;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.teggr.codeagent.runner.ChatMessage;
import com.teggr.codeagent.runner.RunnerManager;
import com.teggr.codeagent.runner.RunnerSession;

@Controller
public class RunnerWebController {

    private final RunnerManager runnerManager;

    public RunnerWebController(RunnerManager runnerManager) {
        this.runnerManager = runnerManager;
    }

    @GetMapping("/")
    public ModelAndView dashboard() {
        ModelAndView view = new ModelAndView("dashboard");
        view.addObject("runners", runnerManager.list());
        return view;
    }

    @PostMapping("/runners")
    public ResponseEntity<Void> startRunner(@RequestParam("repoUrl") String repoUrl,
            @RequestParam("prompt") String prompt) {
        runnerManager.startAsync(repoUrl, prompt);
        return ResponseEntity.status(303).location(URI.create("/")).build();
    }

    @GetMapping("/runners/{runnerId}")
    public ModelAndView runnerDetail(@PathVariable("runnerId") String runnerId) {
        RunnerSession session = runnerManager.getSession(runnerId);
        if (session == null) {
            throw new IllegalArgumentException("No active runner with id " + runnerId);
        }

        ModelAndView view = new ModelAndView("runner");
        view.addObject("runnerId", runnerId);
        view.addObject("repoUrl", session.runner().repoUrl());
        view.addObject("status", session.status());
        view.addObject("messages", session.messages());
        view.addObject("devContainerUri", session.runner().containerLaunch().devContainerUri());
        view.addObject("vscodeReady", session.isReady());
        view.addObject("pendingQuestion", session.pendingQuestion());
        return view;
    }

    @PostMapping("/runners/{runnerId}/prompt")
    public ResponseEntity<Void> sendPrompt(@PathVariable("runnerId") String runnerId,
            @RequestParam("prompt") String prompt) throws Exception {
        RunnerSession session = runnerManager.getSession(runnerId);
        if (session == null) {
            throw new IllegalArgumentException("No active runner with id " + runnerId);
        }
        session.addMessage("user", prompt);
        session.setStatus(com.teggr.codeagent.runner.RunnerStatus.BUSY);
        runnerManager.executor().submit(() -> {
            try {
                session.agentSession().sendPrompt(prompt);
            } catch (Exception e) {
                session.setStatus(com.teggr.codeagent.runner.RunnerStatus.FAILED);
                session.addMessage("assistant", "Error: " + e.getMessage());
            }
        });
        return ResponseEntity.status(303).location(URI.create("/runners/" + runnerId)).build();
    }

    @PostMapping("/runners/{runnerId}/answer")
    public ResponseEntity<Void> answerQuestion(@PathVariable("runnerId") String runnerId,
            @RequestParam("questionId") String questionId, @RequestParam("answer") String answer) {
        RunnerSession session = runnerManager.getSession(runnerId);
        if (session == null) {
            throw new IllegalArgumentException("No active runner with id " + runnerId);
        }
        session.answerQuestion(questionId, answer);
        return ResponseEntity.status(303).location(URI.create("/runners/" + runnerId)).build();
    }

    @PostMapping("/runners/{runnerId}/abort")
    public ResponseEntity<Void> abort(@PathVariable("runnerId") String runnerId) throws Exception {
        RunnerSession session = runnerManager.getSession(runnerId);
        if (session == null) {
            throw new IllegalArgumentException("No active runner with id " + runnerId);
        }
        if (session.agentSession() != null) {
            session.agentSession().abort();
        }
        return ResponseEntity.status(303).location(URI.create("/runners/" + runnerId)).build();
    }

    @GetMapping("/runners/{runnerId}/events")
    public SseEmitter runnerEvents(@PathVariable("runnerId") String runnerId) {
        if (runnerManager.getSession(runnerId) == null) {
            throw new IllegalArgumentException("No active runner with id " + runnerId);
        }
        return runnerManager.subscribe(runnerId);
    }

    @GetMapping("/runners/events")
    public SseEmitter dashboardEvents() {
        return runnerManager.subscribeDashboard();
    }

    @GetMapping("/ui")
    public ResponseEntity<String> ui() {
        String html = renderDashboardHtml();
        return ResponseEntity.ok().header("Content-Type", "text/html; charset=UTF-8").body(html);
    }

    private String renderDashboardHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html>")
          .append("<html><head><meta charset='UTF-8'><title>Code Agent</title>")
          .append("<style>")
          .append("body{font-family:Arial,sans-serif;background:#f4f4f4;margin:0;padding:32px;} .shell{max-width:1100px;margin:0 auto;} ")
          .append(".panel{background:white;border:2px solid #444;border-radius:12px;padding:18px;margin-bottom:24px;box-shadow:4px 4px 0 #d7d7d7;} ")
          .append(".row{display:flex;gap:12px;align-items:center;margin-bottom:12px;} .row input, .row textarea, .row select{flex:1;padding:10px;border:2px solid #333;border-radius:8px;font-size:14px;} ")
          .append(".card{display:flex;align-items:center;justify-content:space-between;padding:12px;border:2px solid #333;border-radius:10px;background:#fafafa;margin-bottom:10px;} ")
          .append(".button{background:#fff;color:#111;border:2px solid #333;border-radius:8px;padding:8px 12px;cursor:pointer;text-decoration:none;} ")
          .append(".meta{font-size:13px;color:#555}; .status{padding:4px 8px;border-radius:999px;border:1px solid #333; font-size:12px;} ")
          .append(".messages{border:2px solid #333;border-radius:10px;padding:12px;background:#fff;} .msg{padding:8px 0;border-bottom:1px solid #ddd;} .msg:last-child{border-bottom:none;} ")
          .append("</style></head><body>")
          .append("<div class='shell'>")
          .append("<div class='panel'>")
          .append("<h2>Start a new runner</h2>")
          .append("<form method='post' action='/runners'>")
          .append("<div class='row'><input name='repoUrl' value='https://github.com/teggr/j2html-toolkit' placeholder='Repository URL'/></div>")
          .append("<div class='row'><textarea name='prompt' rows='5' placeholder='Describe the task you want the agent to run'></textarea></div>")
          .append("<div class='row'><button class='button' type='submit'>Start task</button></div>")
          .append("</form>")
          .append("</div>")
          .append("<div class='panel'><h2>Running runners</h2>");

        List<RunnerSession> sessions = runnerManager.list().stream().toList();
        if (sessions.isEmpty()) {
            sb.append("<p>No active runners.</p>");
        } else {
            for (RunnerSession session : sessions) {
                sb.append("<div class='card'>")
                  .append("<div>")
                  .append("<div><strong>Runner:</strong> ").append(session.runner().id()).append("</div>")
                  .append("<div class='meta'>Repo: ").append(session.runner().repoUrl()).append("</div>")
                  .append("<div class='meta'>Last message: ").append(escapeHtml(session.lastMessage())).append("</div>")
                  .append("</div>")
                  .append("<div class='row'>")
                  .append("<span class='status'>").append(session.status()).append("</span>")
                  .append("<a class='button' href='/runners/").append(session.runner().id()).append("'>Open</a>")
                  .append("</div>")
                  .append("</div>");
            }
        }

        sb.append("</div>")
          .append("</div></body></html>");
        return sb.toString();
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

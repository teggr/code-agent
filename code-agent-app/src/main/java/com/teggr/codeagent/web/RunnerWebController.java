package com.teggr.codeagent.web;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.teggr.codeagent.runner.RunnerManager;
import com.teggr.codeagent.runner.RunnerSession;

@Controller
public class RunnerWebController {

    private final RunnerManager runnerManager;
    private final GitHubRepositoryService gitHubRepositoryService;

    public RunnerWebController(RunnerManager runnerManager, GitHubRepositoryService gitHubRepositoryService) {
        this.runnerManager = runnerManager;
        this.gitHubRepositoryService = gitHubRepositoryService;
    }

    @GetMapping("/")
    public ModelAndView dashboard() {
        ModelAndView view = new ModelAndView("dashboard");
        view.addObject("runners", runnerManager.list());
        return view;
    }

    @GetMapping("/repositories")
    public ModelAndView repositoryResults(@RequestParam(value = "query", defaultValue = "") String query,
            @RequestParam(value = "page", defaultValue = "1") int page) {
        ModelAndView view = new ModelAndView("fragments :: repositoryResults");
        GitHubRepositoryService.RepositoryPage repositoryPage = gitHubRepositoryService.findRepositories(query, page);
        view.addObject("repositories", repositoryPage.repositories());
        view.addObject("query", query);
        view.addObject("nextPage", Math.max(page, 1) + 1);
        view.addObject("hasMore", repositoryPage.hasMore());
        view.addObject("unavailable", repositoryPage.unavailable());
        return view;
    }

    @GetMapping("/repositories/selection")
    public ModelAndView repositorySelection(@RequestParam("repoUrl") String repoUrl,
            @RequestParam("fullName") String fullName) {
        ModelAndView view = new ModelAndView("fragments :: repositoryPicker");
        view.addObject("selectedRepositoryUrl", repoUrl);
        view.addObject("selectedRepositoryName", fullName);
        return view;
    }

    @GetMapping("/repositories/picker")
    public ModelAndView repositoryPicker() {
        return new ModelAndView("fragments :: repositoryPicker");
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
        view.addObject("sessionId", session.id());
        view.addObject("sessions", runnerManager.sessions(runnerId));
        view.addObject("repoUrl", session.runner().repoUrl());
        view.addObject("status", session.status());
        view.addObject("messages", session.messages());
        view.addObject("devContainerUri", session.runner().containerLaunch().devContainerUri());
        view.addObject("vscodeReady", session.isReady());
        view.addObject("promptable", session.isReady());
        view.addObject("pendingQuestion", session.pendingQuestion());
        return view;
    }

    @PostMapping("/runners/{runnerId}/sessions")
    public ResponseEntity<Void> createSession(@PathVariable("runnerId") String runnerId) throws Exception {
        RunnerSession session = runnerManager.createSession(runnerId);
        return ResponseEntity.status(303)
                .location(URI.create("/runners/" + runnerId + "/sessions/" + session.id()))
                .build();
    }

    @GetMapping("/runners/{runnerId}/sessions/{sessionId}")
    public ModelAndView sessionDetail(@PathVariable("runnerId") String runnerId,
            @PathVariable("sessionId") String sessionId) {
        RunnerSession session = requireSession(runnerId, sessionId);
        ModelAndView view = sessionView(runnerId, sessionId, session);
        view.addObject("sessions", runnerManager.sessions(runnerId));
        return view;
    }

    @PostMapping("/runners/{runnerId}/sessions/{sessionId}/prompt")
    public ResponseEntity<Void> sendSessionPrompt(@PathVariable("runnerId") String runnerId,
            @PathVariable("sessionId") String sessionId, @RequestParam("prompt") String prompt) {
        RunnerSession session = requireSession(runnerId, sessionId);
        sendPrompt(session, prompt);
        return redirectToSession(runnerId, sessionId);
    }

    @PostMapping("/runners/{runnerId}/sessions/{sessionId}/answer")
    public ResponseEntity<Void> answerSessionQuestion(@PathVariable("runnerId") String runnerId,
            @PathVariable("sessionId") String sessionId, @RequestParam("questionId") String questionId,
            @RequestParam("answer") String answer) {
        requireSession(runnerId, sessionId).answerQuestion(questionId, answer);
        return redirectToSession(runnerId, sessionId);
    }

    @PostMapping("/runners/{runnerId}/sessions/{sessionId}/abort")
    public ResponseEntity<Void> abortSession(@PathVariable("runnerId") String runnerId,
            @PathVariable("sessionId") String sessionId) throws Exception {
        RunnerSession session = requireSession(runnerId, sessionId);
        if (session.agentSession() != null) {
            session.agentSession().abort();
        }
        return redirectToSession(runnerId, sessionId);
    }

    @PostMapping("/runners/{runnerId}/sessions/{sessionId}/remove")
    public ResponseEntity<Void> removeSession(@PathVariable("runnerId") String runnerId,
            @PathVariable("sessionId") String sessionId) {
        requireSession(runnerId, sessionId);
        runnerManager.removeSession(runnerId, sessionId);
        return ResponseEntity.status(303).location(URI.create("/runners/" + runnerId)).build();
    }

    @GetMapping("/runners/{runnerId}/sessions/{sessionId}/events")
    public SseEmitter sessionEvents(@PathVariable("runnerId") String runnerId,
            @PathVariable("sessionId") String sessionId) {
        requireSession(runnerId, sessionId);
        return runnerManager.subscribeSession(sessionId);
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

    @PostMapping("/runners/{runnerId}/stop")
    public ResponseEntity<Void> stopRunner(@PathVariable("runnerId") String runnerId) {
        requireRunner(runnerId);
        runnerManager.stop(runnerId);
        return ResponseEntity.status(303).location(URI.create("/runners/" + runnerId)).build();
    }

    @PostMapping("/runners/{runnerId}/start")
    public ResponseEntity<Void> startRunner(@PathVariable("runnerId") String runnerId) {
        requireRunner(runnerId);
        runnerManager.restart(runnerId);
        return ResponseEntity.status(303).location(URI.create("/runners/" + runnerId)).build();
    }

    @PostMapping("/runners/{runnerId}/remove")
    public ResponseEntity<Void> removeRunner(@PathVariable("runnerId") String runnerId) {
        requireRunner(runnerId);
        runnerManager.remove(runnerId);
        return ResponseEntity.status(303).location(URI.create("/")).build();
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

    private ModelAndView sessionView(String runnerId, String sessionId, RunnerSession session) {
        ModelAndView view = new ModelAndView("runner");
        view.addObject("runnerId", runnerId);
        view.addObject("sessionId", sessionId);
        view.addObject("repoUrl", session.runner().repoUrl());
        view.addObject("status", session.status());
        view.addObject("messages", session.messages());
        view.addObject("devContainerUri", session.runner().containerLaunch().devContainerUri());
        view.addObject("vscodeReady", session.isReady());
        view.addObject("promptable", session.isReady());
        view.addObject("pendingQuestion", session.pendingQuestion());
        return view;
    }

    private void sendPrompt(RunnerSession session, String prompt) {
        if (session.agentSession() == null) {
            throw new IllegalStateException("Session " + session.id() + " has no connected agent");
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
    }

    private ResponseEntity<Void> redirectToSession(String runnerId, String sessionId) {
        return ResponseEntity.status(303).location(URI.create("/runners/" + runnerId + "/sessions/" + sessionId)).build();
    }

    private RunnerSession requireSession(String runnerId, String sessionId) {
        RunnerSession session = runnerManager.getSession(runnerId, sessionId);
        if (session == null) {
            throw new IllegalArgumentException("No session " + sessionId + " on runner " + runnerId);
        }
        return session;
    }

    private RunnerSession requireRunner(String runnerId) {
        RunnerSession session = runnerManager.getSession(runnerId);
        if (session == null) {
            throw new IllegalArgumentException("No active runner with id " + runnerId);
        }
        return session;
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

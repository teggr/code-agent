package com.teggr.codeagent.harness.copilot;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionListFilter;
import com.github.copilot.rpc.ElicitationResult;
import com.github.copilot.rpc.ElicitationResultAction;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.UserInputResponse;
import com.teggr.codeagent.harness.AgentHarness;
import com.teggr.codeagent.harness.HarnessSession;
import com.teggr.codeagent.harness.Question;

class CopilotAgentHarness implements AgentHarness {

    private final CopilotClient client;
    private final String workspacePath;

    CopilotAgentHarness(CopilotClient client, String workspacePath) {
        this.client = client;
        this.workspacePath = workspacePath;
    }

    @Override
    public HarnessSession createSession(String sessionId) throws Exception {
        AtomicReference<Function<Question, CompletableFuture<String>>> questionHandlerRef = questionHandlerRef();
        var session = client.createSession(baseSessionConfig(questionHandlerRef)
                .setSessionId(sessionId))
                .get();
        return new CopilotHarnessSession(session, questionHandlerRef);
    }

    @Override
    public HarnessSession resumeSession(String sessionId) throws Exception {
        AtomicReference<Function<Question, CompletableFuture<String>>> questionHandlerRef = questionHandlerRef();
        var session = client.resumeSession(sessionId, baseResumeSessionConfig(questionHandlerRef)).get();
        return new CopilotHarnessSession(session, questionHandlerRef);
    }

    @Override
    public List<String> listSessionIds() throws Exception {
        return client.listSessions(new SessionListFilter().setCwd(workspacePath)).get().stream()
                .map(com.github.copilot.rpc.SessionMetadata::getSessionId)
                .collect(Collectors.toList());
    }

    private AtomicReference<Function<Question, CompletableFuture<String>>> questionHandlerRef() {
        // Holds the conversation-supplied question handler, which is unknown until after the SDK session exists.
        return new AtomicReference<>();
    }

    private SessionConfig baseSessionConfig(
            AtomicReference<Function<Question, CompletableFuture<String>>> questionHandlerRef) {
        return new SessionConfig()
                .setWorkingDirectory(workspacePath)
                .setEnableSessionStore(true)
                .setOnPermissionRequest(PermissionHandler.APPROVE_ALL)
                .setOnUserInputRequest((request, invocation) -> {
                    var handler = questionHandlerRef.get();
                    if (handler == null) {
                        return CompletableFuture.completedFuture(
                                new UserInputResponse().setAnswer("").setWasFreeform(true));
                    }
                    Question question = new Question(UUID.randomUUID().toString(), request.getQuestion(),
                            request.getChoices() == null ? List.of() : request.getChoices());
                    return handler.apply(question)
                            .thenApply(answer -> new UserInputResponse().setAnswer(answer)
                                    .setWasFreeform(request.getChoices() == null || !request.getChoices().contains(answer)));
                })
                .setOnElicitationRequest(context -> {
                    var handler = questionHandlerRef.get();
                    if (handler == null) {
                        return CompletableFuture.completedFuture(
                                new ElicitationResult().setAction(ElicitationResultAction.CANCEL));
                    }
                    Question question = new Question(UUID.randomUUID().toString(), context.getMessage(), List.of());
                    return handler.apply(question)
                            .thenApply(answer -> new ElicitationResult().setAction(ElicitationResultAction.ACCEPT)
                                    .setContent(java.util.Map.of("answer", answer)));
                });
    }

    private ResumeSessionConfig baseResumeSessionConfig(
            AtomicReference<Function<Question, CompletableFuture<String>>> questionHandlerRef) {
        return new ResumeSessionConfig()
                .setWorkingDirectory(workspacePath)
                .setEnableSessionStore(true)
                .setOnPermissionRequest(PermissionHandler.APPROVE_ALL)
                .setOnUserInputRequest((request, invocation) -> {
                    var handler = questionHandlerRef.get();
                    if (handler == null) {
                        return CompletableFuture.completedFuture(
                                new UserInputResponse().setAnswer("").setWasFreeform(true));
                    }
                    Question question = new Question(UUID.randomUUID().toString(), request.getQuestion(),
                            request.getChoices() == null ? List.of() : request.getChoices());
                    return handler.apply(question)
                            .thenApply(answer -> new UserInputResponse().setAnswer(answer)
                                    .setWasFreeform(request.getChoices() == null || !request.getChoices().contains(answer)));
                })
                .setOnElicitationRequest(context -> {
                    var handler = questionHandlerRef.get();
                    if (handler == null) {
                        return CompletableFuture.completedFuture(
                                new ElicitationResult().setAction(ElicitationResultAction.CANCEL));
                    }
                    Question question = new Question(UUID.randomUUID().toString(), context.getMessage(), List.of());
                    return handler.apply(question)
                            .thenApply(answer -> new ElicitationResult().setAction(ElicitationResultAction.ACCEPT)
                                    .setContent(java.util.Map.of("answer", answer)));
                });
    }

    @Override
    public void close() throws Exception {
        client.close();
    }

}

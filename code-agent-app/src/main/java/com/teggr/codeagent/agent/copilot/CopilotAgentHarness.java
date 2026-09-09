package com.teggr.codeagent.agent.copilot;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.ElicitationResult;
import com.github.copilot.rpc.ElicitationResultAction;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.UserInputResponse;
import com.teggr.codeagent.agent.AgentHarness;
import com.teggr.codeagent.agent.AgentSession;
import com.teggr.codeagent.agent.Question;

class CopilotAgentHarness implements AgentHarness {

    private final CopilotClient client;

    CopilotAgentHarness(CopilotClient client) {
        this.client = client;
    }

    @Override
    public AgentSession createSession() throws Exception {
        // Holds the RunnerSession-supplied question handler, which isn't known until after the SDK session exists.
        AtomicReference<Function<Question, CompletableFuture<String>>> questionHandlerRef = new AtomicReference<>();
        var session = client.createSession(
            new SessionConfig()
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
                })
        ).get();
        return new CopilotAgentSession(session, questionHandlerRef);
    }

    @Override
    public void close() throws Exception {
        client.close();
    }

}

package com.teggr.codeagent.agent.copilot;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AbortEvent;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.AssistantTurnEndEvent;
import com.github.copilot.generated.AssistantTurnStartEvent;
import com.github.copilot.generated.AssistantUsageEvent;
import com.github.copilot.generated.CommandCompletedEvent;
import com.github.copilot.generated.CommandQueuedEvent;
import com.github.copilot.generated.HookEndEvent;
import com.github.copilot.generated.HookStartEvent;
import com.github.copilot.generated.PermissionCompletedEvent;
import com.github.copilot.generated.PermissionRequestedEvent;
import com.github.copilot.generated.SessionCompactionCompleteEvent;
import com.github.copilot.generated.SessionCompactionStartEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.generated.SessionInfoEvent;
import com.github.copilot.generated.SessionResumeEvent;
import com.github.copilot.generated.SessionShutdownEvent;
import com.github.copilot.generated.SessionStartEvent;
import com.github.copilot.generated.SessionTruncationEvent;
import com.github.copilot.generated.SessionUsageInfoEvent;
import com.github.copilot.generated.SessionWorkspaceFileChangedEvent;
import com.github.copilot.generated.SkillInvokedEvent;
import com.github.copilot.generated.SubagentCompletedEvent;
import com.github.copilot.generated.SubagentDeselectedEvent;
import com.github.copilot.generated.SubagentFailedEvent;
import com.github.copilot.generated.SubagentSelectedEvent;
import com.github.copilot.generated.SubagentStartedEvent;
import com.github.copilot.generated.ToolExecutionCompleteEvent;
import com.github.copilot.generated.ToolExecutionStartEvent;
import com.github.copilot.rpc.MessageOptions;
import com.teggr.codeagent.agent.AgentEvent;
import com.teggr.codeagent.agent.AgentSession;
import com.teggr.codeagent.agent.Question;
import com.teggr.codeagent.agent.ToolActivity;

class CopilotAgentSession implements AgentSession {

    private final CopilotSession session;
    private final AtomicReference<Function<Question, CompletableFuture<String>>> questionHandlerRef;
    private final Map<String, String> toolNamesByCallId = new ConcurrentHashMap<>();

    CopilotAgentSession(CopilotSession session,
            AtomicReference<Function<Question, CompletableFuture<String>>> questionHandlerRef) {
        this.session = session;
        this.questionHandlerRef = questionHandlerRef;
    }

    @Override
    public void sendPrompt(String prompt) throws Exception {
        session.send(new MessageOptions().setPrompt(prompt)).get();
    }

    @Override
    public void onMessage(Consumer<String> listener) {
        session.on(AssistantMessageEvent.class, event -> listener.accept(event.getData().content()));
    }

    @Override
    public void onIdle(Runnable listener) {
        session.on(SessionIdleEvent.class, event -> listener.run());
    }

    @Override
    public void onError(Consumer<String> listener) {
        session.on(SessionErrorEvent.class, event -> listener.accept(event.getData().message()));
    }

    @Override
    public void onToolActivity(Consumer<ToolActivity> listener) {
        session.on(ToolExecutionStartEvent.class, event -> {
            var data = event.getData();
            toolNamesByCallId.put(data.toolCallId(), data.toolName());
            listener.accept(new ToolActivity(data.toolCallId(), data.toolName(), ToolActivity.Phase.STARTED,
                    "Running " + data.toolName() + "..."));
        });
        session.on(ToolExecutionCompleteEvent.class, event -> {
            var data = event.getData();
            String toolName = toolNamesByCallId.getOrDefault(data.toolCallId(), data.toolCallId());
            boolean success = data.success() == null || data.success();
            String summary = (success ? "Done " : "Failed ") + toolName;
            listener.accept(new ToolActivity(data.toolCallId(), toolName, ToolActivity.Phase.COMPLETED, summary));
        });
    }

    @Override
    public void onEvent(Consumer<AgentEvent> listener) {
        session.on(event -> {
            String summary = summarize(event);
            if (summary != null) {
                listener.accept(new AgentEvent(event.getType(), summary));
            }
        });
    }

    @Override
    public void onQuestion(Function<Question, CompletableFuture<String>> handler) {
        questionHandlerRef.set(handler);
    }

    @Override
    public void abort() throws Exception {
        session.abort().get();
    }

    /** Maps known SDK events to a human-readable summary; returns null for events already surfaced elsewhere. */
    private static String summarize(SessionEvent event) {
        return switch (event) {
            case SessionStartEvent e -> "Session started";
            case SessionResumeEvent e -> "Session resumed";
            case SessionShutdownEvent e -> "Session shutting down"
                    + (e.getData().errorReason() != null ? ": " + e.getData().errorReason() : "");
            case SessionInfoEvent e -> e.getData().message();
            case SessionWorkspaceFileChangedEvent e -> "Modified: " + e.getData().path();
            case SessionUsageInfoEvent e -> "Usage: " + e.getData().currentTokens() + "/" + e.getData().tokenLimit()
                    + " tokens";
            case AssistantUsageEvent e -> "Turn used " + e.getData().inputTokens() + " in / "
                    + e.getData().outputTokens() + " out tokens";
            case SessionTruncationEvent e -> "Context truncated (removed " + e.getData().tokensRemovedDuringTruncation()
                    + " tokens)";
            case SessionCompactionStartEvent e -> "Context compaction started";
            case SessionCompactionCompleteEvent e -> "Context compaction "
                    + (Boolean.TRUE.equals(e.getData().success()) ? "completed" : "failed")
                    + " (removed " + e.getData().tokensRemoved() + " tokens)";
            case AssistantTurnStartEvent e -> "Agent is thinking...";
            case AssistantTurnEndEvent e -> "Agent finished this turn";
            case SubagentStartedEvent e -> "Subagent '" + e.getData().agentName() + "' started";
            case SubagentSelectedEvent e -> "Subagent '" + e.getData().agentName() + "' selected";
            case SubagentDeselectedEvent e -> "Subagent deselected";
            case SubagentCompletedEvent e -> "Subagent '" + e.getData().agentName() + "' completed";
            case SubagentFailedEvent e -> "Subagent '" + e.getData().agentName() + "' failed: " + e.getData().error();
            case HookStartEvent e -> "Hook '" + e.getData().hookType() + "' started";
            case HookEndEvent e -> "Hook '" + e.getData().hookType() + "' "
                    + (Boolean.TRUE.equals(e.getData().success()) ? "completed" : "failed");
            case SkillInvokedEvent e -> "Skill '" + e.getData().name() + "' invoked";
            case CommandQueuedEvent e -> "Command queued: " + e.getData().command();
            case CommandCompletedEvent e -> "Command completed";
            case AbortEvent e -> "Operation aborted";
            case PermissionRequestedEvent e -> "Permission requested";
            case PermissionCompletedEvent e -> "Permission approved";
            default -> null;
        };
    }

}


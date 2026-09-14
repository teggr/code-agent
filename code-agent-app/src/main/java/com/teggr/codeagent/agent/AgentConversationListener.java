package com.teggr.codeagent.agent;

import com.teggr.codeagent.harness.Question;

public interface AgentConversationListener {

    void onMessage(AgentConversation conversation, ChatMessage message);

    void onStatusChange(AgentConversation conversation, AgentConversationStatus status);

    void onQuestionChange(AgentConversation conversation, Question question);
}
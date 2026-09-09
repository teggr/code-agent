package com.teggr.codeagent.agent;

import java.util.List;

/** A question the agent is asking the user (via ask_user or an elicitation request). */
public record Question(String id, String prompt, List<String> choices) {
}

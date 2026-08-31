package io.cloudagent.gateway.session;

import java.time.Instant;

/**
 * Persisted metadata for a gateway session, stored in SQLite so sessions survive gateway
 * restarts and can be listed/reconnected without needing the in-memory runtime state.
 */
public record SessionRecord(
        String sessionId,
        String agentType,
        String containerName,
        boolean persistent,
        SessionStatus status,
        Instant createdAt,
        Instant updatedAt) {
}

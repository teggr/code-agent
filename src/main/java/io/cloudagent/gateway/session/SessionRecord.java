package io.cloudagent.gateway.session;

import java.time.Instant;

/**
 * Persisted metadata for a gateway session, stored in SQLite so sessions survive gateway
 * restarts and can be listed/reconnected without needing the in-memory runtime state.
 *
 * <p>{@code hostPort} is the 127.0.0.1 port the container's Copilot port was published on; it is
 * persisted so a reconnect reuses the very same mapping instead of allocating a new one.
 */
public record SessionRecord(
        String sessionId,
        String agentType,
        String containerName,
        int hostPort,
        boolean persistent,
        SessionStatus status,
        Instant createdAt,
        Instant updatedAt) {
}

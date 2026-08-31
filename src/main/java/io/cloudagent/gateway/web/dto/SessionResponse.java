package io.cloudagent.gateway.web.dto;

import io.cloudagent.gateway.session.SessionRecord;
import java.time.Instant;

/** REST representation of a {@link SessionRecord}. */
public record SessionResponse(
        String sessionId,
        String agentType,
        String containerName,
        boolean persistent,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static SessionResponse from(SessionRecord record) {
        return new SessionResponse(
                record.sessionId(),
                record.agentType(),
                record.containerName(),
                record.persistent(),
                record.status().name(),
                record.createdAt(),
                record.updatedAt());
    }
}

package io.cloudagent.gateway.session;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQLite-backed store for {@link SessionRecord} metadata. */
@Repository
public class SessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public SessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(SessionRecord record) {
        int updated = jdbcTemplate.update("""
                UPDATE sessions
                SET agent_type = ?, container_name = ?, persistent = ?, status = ?, updated_at = ?
                WHERE session_id = ?
                """,
                record.agentType(), record.containerName(), record.persistent(),
                record.status().name(), Timestamp.from(record.updatedAt()), record.sessionId());
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO sessions (session_id, agent_type, container_name, persistent, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    record.sessionId(), record.agentType(), record.containerName(), record.persistent(),
                    record.status().name(), Timestamp.from(record.createdAt()), Timestamp.from(record.updatedAt()));
        }
    }

    public Optional<SessionRecord> findById(String sessionId) {
        List<SessionRecord> results = jdbcTemplate.query(
                "SELECT * FROM sessions WHERE session_id = ?", this::mapRow, sessionId);
        return results.stream().findFirst();
    }

    public List<SessionRecord> findAll() {
        return jdbcTemplate.query("SELECT * FROM sessions ORDER BY created_at DESC", this::mapRow);
    }

    public void delete(String sessionId) {
        jdbcTemplate.update("DELETE FROM sessions WHERE session_id = ?", sessionId);
    }

    private SessionRecord mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SessionRecord(
                rs.getString("session_id"),
                rs.getString("agent_type"),
                rs.getString("container_name"),
                rs.getBoolean("persistent"),
                SessionStatus.valueOf(rs.getString("status")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
